package id.neotica.minidroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread

class EmulatorService : Service() {

    companion object {
        const val VNC_PORT = 5901
        const val ADB_PORT = 15555

        const val ACTION_START = "id.neotica.minidroid.START"
        const val ACTION_STOP = "id.neotica.minidroid.STOP"
        const val ACTION_RESTART = "id.neotica.minidroid.RESTART"

        private const val CHANNEL_ID = "minidroid_vm"
        private const val NOTIF_ID = 1001

        @Volatile
        var currentState: String = "Stopped"
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        private val listeners = mutableSetOf<(String, Boolean) -> Unit>()

        fun addStateListener(listener: (String, Boolean) -> Unit) {
            synchronized(listeners) {
                listeners.add(listener)
            }
            listener(currentState, isRunning)
        }

        fun removeStateListener(listener: (String, Boolean) -> Unit) {
            synchronized(listeners) {
                listeners.remove(listener)
            }
        }

        private fun updateState(state: String, running: Boolean, error: String? = null) {
            currentState = state
            isRunning = running
            if (error != null) lastError = error
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { it(state, running) }
        }

        fun startVm(context: Context) {
            val intent = Intent(context, EmulatorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun restartVm(context: Context) {
            val intent = Intent(context, EmulatorService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopVm(context: Context) {
            val intent = Intent(context, EmulatorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private var qemuProcess: Process? = null
    private var httpServer: SharedHttpServer? = null
    private val procLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopQemuProcess()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                beginForeground("Restarting Android 1.6...")
                thread(name = "MiniDroidRestart") {
                    stopQemuProcess()
                    launchQemu()
                }
            }
            else -> {
                beginForeground("Starting Android 1.6...")
                synchronized(procLock) {
                    if (qemuProcess?.isAlive == true) {
                        return START_STICKY
                    }
                }
                thread(name = "MiniDroidLaunch") {
                    launchQemu()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopQemuProcess()
        super.onDestroy()
    }

    private fun launchQemu() {
        try {
            lastError = null
            updateState("Preparing Android 1.6 images...", false)
            ImageInstaller.ensureImagesExtracted(this) { msg ->
                updateState(msg, false)
            }

            val qemuBin = File(applicationInfo.nativeLibraryDir, "libqemu.so")
            if (!qemuBin.exists()) {
                val err = "Missing libqemu.so in ${applicationInfo.nativeLibraryDir}"
                updateState("Error: $err", false, err)
                return
            }

            val bootLog = ImageInstaller.bootLogFile(this)
            bootLog.delete()

            val machineArg = buildString {
                append("goldfish")
                append(",system=").append(ImageInstaller.systemFile(this@EmulatorService).absolutePath)
                append(",userdata=").append(ImageInstaller.userdataFile(this@EmulatorService).absolutePath)
                append(",cache=").append(ImageInstaller.cacheFile(this@EmulatorService).absolutePath)
                append(",sdcard=").append(ImageInstaller.sdcardFile(this@EmulatorService).absolutePath)
                append(",width=320,height=480")
            }

            val cmd = listOf(
                qemuBin.absolutePath,
                "-L", filesDir.absolutePath,
                "-M", machineArg,
                "-m", "128M",
                "-kernel", ImageInstaller.kernelFile(this).absolutePath,
                "-initrd", ImageInstaller.ramdiskFile(this).absolutePath,
                "-append", "qemu=1 console=ttyS0 android.checkjni=1 android.ndns=1",
                "-nic", "user,model=smc91c111,hostfwd=tcp:127.0.0.1:$ADB_PORT-:5555",
                "-serial", "file:${bootLog.absolutePath}",
                "-vnc", "127.0.0.1:1",
                "-display", "none"
            )

            val pb = ProcessBuilder(cmd)
            pb.directory(filesDir)
            pb.environment()["TMPDIR"] = cacheDir.absolutePath
            pb.redirectErrorStream(true)

            if (httpServer == null) {
                httpServer = SharedHttpServer(applicationContext).apply { start() }
            }

            updateState("Booting Android 1.6...", true)
            val proc = pb.start()
            synchronized(procLock) {
                qemuProcess = proc
            }

            // Capture QEMU stderr/stdout into a log file for easy debugging
            val qemuLog = File(filesDir, "qemu.log")
            val logThread = thread(name = "QemuLogReader", isDaemon = true) {
                try {
                    proc.inputStream.use { input ->
                        qemuLog.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val exitCode = proc.waitFor()
            try {
                logThread.join(1000)
            } catch (_: InterruptedException) {
            }
            synchronized(procLock) {
                if (qemuProcess === proc) {
                    qemuProcess = null
                }
            }
            if (isRunning) {
                val errTail = try {
                    qemuLog.takeIf { it.exists() }?.readText()?.takeLast(300) ?: ""
                } catch (_: Exception) {
                    ""
                }
                val msg = if (exitCode == 0) "Stopped" else "QEMU exited ($exitCode): $errTail"
                updateState(msg, false, if (exitCode != 0) msg else null)
            }
        } catch (e: Exception) {
            val msg = "Launch failed: ${e.message}"
            updateState(msg, false, msg)
        }
    }

    private fun stopQemuProcess() {
        httpServer?.stop()
        httpServer = null
        synchronized(procLock) {
            val p = qemuProcess
            qemuProcess = null
            if (p != null) {
                try {
                    p.destroy()
                    if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly()
                    }
                } catch (_: Exception) {
                }
            }
        }
        updateState("Stopped", false)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "MiniDroid Emulator",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun beginForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MiniDroid (Android 1.6)")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
