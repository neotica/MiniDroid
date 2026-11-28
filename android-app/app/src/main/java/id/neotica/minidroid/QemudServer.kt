package id.neotica.minidroid

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Android 1.6 (Donut) QEMUD serial multiplexer on 127.0.0.1:15556 (/dev/ttyS1, android.qemud=ttyS1).
 *
 * Multiplexes:
 *  - "gsm"             -> Virtual 3G AT modem (Mobile Data + NITZ time sync)
 *  - "sensors"         -> Live host phone Accelerometer, Magnetic Field, and Orientation via SensorManager
 *  - "boot-properties" -> Cleanly completes /system/bin/qemu-props at boot
 *  - "hw-control"      -> Accepts system_server backlight/power control messages
 */
class QemudServer(
    private val context: Context,
    private val port: Int = EmulatorService.QEMUD_PORT
) : SensorEventListener {

    companion object {
        private const val PREFS = "minidroid_prefs"
        private const val KEY_LOCK_PORTRAIT = "lock_portrait"

        private const val BIT_ACCEL = 1 shl 0
        private const val BIT_MAG = 1 shl 1
        private const val BIT_ORIENT = 1 shl 2
        private const val BIT_TEMP = 1 shl 3
        private const val SENSOR_LIST_MASK = BIT_ACCEL or BIT_MAG or BIT_ORIENT // 7

        @Volatile
        private var activeInstance: QemudServer? = null

        fun isPortraitLocked(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCK_PORTRAIT, false)
        }

        fun setPortraitLocked(context: Context, locked: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOCK_PORTRAIT, locked)
                .apply()
            activeInstance?.lockPortrait = locked
        }
    }

    @Volatile
    private var running = false
    @Volatile
    var lockPortrait: Boolean = isPortraitLocked(context)
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null
    private var scheduler: ScheduledExecutorService? = null

    private val sensorManager: SensorManager? by lazy {
        context.getSystemService(SensorManager::class.java)
    }

    @Volatile
    private var hostSensorsRegistered = false

    // Default upright portrait gravity until host accelerometer delivers live readings
    private val sensorLock = Any()
    private val accel = floatArrayOf(0f, 8.495f, 4.905f)
    private val mag = floatArrayOf(0f, 25f, -40f)
    private val orient = floatArrayOf(0f, -30f, 0f)
    private val rotMatrix = FloatArray(9)
    private val orientRad = FloatArray(3)
    private var hasMag = false

    fun start() {
        if (running) return
        lockPortrait = isPortraitLocked(context)
        activeInstance = this
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
        }
        serverSocket = ss
        running = true
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "MiniDroidQemudSensors").apply { isDaemon = true }
        }

        thread(name = "MiniDroidQemud", isDaemon = true) {
            try {
                while (running) {
                    val sock = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    clientSocket = sock
                    handleSession(sock)
                }
            } finally {
                unregisterHostSensors()
                try {
                    ss.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        running = false
        if (activeInstance === this) {
            activeInstance = null
        }
        unregisterHostSensors()
        scheduler?.shutdownNow()
        scheduler = null
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        clientSocket = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(sensorLock) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accel[0] = event.values[0]
                    accel[1] = event.values[1]
                    accel[2] = event.values[2]
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    mag[0] = event.values[0]
                    mag[1] = event.values[1]
                    mag[2] = event.values[2]
                    hasMag = true
                }
                else -> return
            }
            if (hasMag && SensorManager.getRotationMatrix(rotMatrix, null, accel, mag)) {
                SensorManager.getOrientation(rotMatrix, orientRad)
                var az = Math.toDegrees(orientRad[0].toDouble()).toFloat()
                if (az < 0f) az += 360f
                val pitch = Math.toDegrees(orientRad[1].toDouble()).toFloat()
                val roll = -Math.toDegrees(orientRad[2].toDouble()).toFloat()
                orient[0] = az
                orient[1] = pitch
                orient[2] = roll
            } else {
                // Fallback pitch/roll estimate from accelerometer when magnetometer isn't present
                val ax = accel[0].toDouble()
                val ay = accel[1].toDouble()
                val az = accel[2].toDouble()
                orient[1] = Math.toDegrees(kotlin.math.atan2(-ay, kotlin.math.sqrt(ax * ax + az * az))).toFloat()
                orient[2] = Math.toDegrees(kotlin.math.atan2(ax, az)).toFloat()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun updateHostSensorRegistration(anyActive: Boolean) {
        val sm = sensorManager ?: return
        if (anyActive && !hostSensorsRegistered) {
            try {
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                hostSensorsRegistered = true
            } catch (_: Exception) {
            }
        } else if (!anyActive && hostSensorsRegistered) {
            unregisterHostSensors()
        }
    }

    private fun unregisterHostSensors() {
        if (!hostSensorsRegistered) return
        hostSensorsRegistered = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }
    }

    private class ChannelState(
        val id: Int,
        val service: String
    ) {
        val rxBuf = ByteArrayOutputStream()
        @Volatile var enabledMask: Int = 0
        @Volatile var delayMs: Long = 200L
        var timerTask: ScheduledFuture<*>? = null
    }

    private fun handleSession(sock: Socket) {
        val writeLock = Any()
        val channels = ConcurrentHashMap<Int, ChannelState>()
        val modem = VirtualModemServer.ModemState()

        sock.use { s ->
            s.tcpNoDelay = true
            val input = DataInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream(), 4096)

            fun sendPacket(ch: Int, payload: ByteArray, framed: Boolean = false) {
                val data = if (framed) {
                    val frameHdr = String.format(Locale.US, "%04x", payload.size)
                        .toByteArray(Charsets.US_ASCII)
                    frameHdr + payload
                } else {
                    payload
                }
                if (data.isEmpty()) return
                // Fragment to serial MTU (4000 bytes) if ever needed
                var off = 0
                while (off < data.size) {
                    val chunkLen = minOf(4000, data.size - off)
                    val hdr = String.format(Locale.US, "%02x%04x", ch, chunkLen)
                        .toByteArray(Charsets.US_ASCII)
                    synchronized(writeLock) {
                        try {
                            output.write(hdr)
                            output.write(data, off, chunkLen)
                            output.flush()
                        } catch (_: Exception) {
                            return
                        }
                    }
                    off += chunkLen
                }
            }

            fun sendControl(msg: String) {
                sendPacket(0, msg.toByteArray(Charsets.ISO_8859_1), framed = false)
            }

            fun refreshHostSensors() {
                val anyActive = channels.values.any { it.service == "sensors" && it.enabledMask != 0 }
                updateHostSensorRegistration(anyActive)
            }

            fun sendSensorReport(ch: ChannelState) {
                val mask = ch.enabledMask
                if (mask == 0) return
                val ax: Float
                val ay: Float
                val az: Float
                val mx: Float
                val my: Float
                val mz: Float
                val oa: Float
                val op: Float
                val or: Float
                if (lockPortrait) {
                    // Hold steady upright portrait gravity so Android 1.6 never auto-rotates
                    ax = 0f; ay = 8.495f; az = 4.905f
                    oa = 0f; op = -30f; or = 0f
                    synchronized(sensorLock) {
                        mx = mag[0]; my = mag[1]; mz = mag[2]
                    }
                } else {
                    synchronized(sensorLock) {
                        ax = accel[0]; ay = accel[1]; az = accel[2]
                        mx = mag[0]; my = mag[1]; mz = mag[2]
                        oa = orient[0]; op = orient[1]; or = orient[2]
                    }
                }
                if ((mask and BIT_ACCEL) != 0) {
                    val line = String.format(Locale.US, "acceleration:%.4f:%.4f:%.4f", ax, ay, az)
                    sendPacket(ch.id, line.toByteArray(Charsets.US_ASCII), framed = true)
                }
                if ((mask and BIT_MAG) != 0) {
                    // Note: Android 1.6 sensors.goldfish.so parses "magnetic:%g:%g:%g"
                    val line = String.format(Locale.US, "magnetic:%.4f:%.4f:%.4f", mx, my, mz)
                    sendPacket(ch.id, line.toByteArray(Charsets.US_ASCII), framed = true)
                }
                if ((mask and BIT_ORIENT) != 0) {
                    val line = String.format(Locale.US, "orientation:%.3f:%.3f:%.3f", oa, op, or)
                    sendPacket(ch.id, line.toByteArray(Charsets.US_ASCII), framed = true)
                }
                if ((mask and BIT_TEMP) != 0) {
                    sendPacket(ch.id, "temperature:25.0".toByteArray(Charsets.US_ASCII), framed = true)
                }
                val us = SystemClock.elapsedRealtime() * 1000L
                sendPacket(ch.id, "sync:$us".toByteArray(Charsets.US_ASCII), framed = true)
            }

            fun rescheduleSensorTimer(ch: ChannelState) {
                ch.timerTask?.cancel(false)
                ch.timerTask = null
                if (ch.enabledMask != 0 && running) {
                    val period = ch.delayMs.coerceIn(20L, 1000L)
                    ch.timerTask = scheduler?.scheduleWithFixedDelay(
                        {
                            if (running && channels.containsKey(ch.id) && ch.enabledMask != 0) {
                                sendSensorReport(ch)
                            }
                        },
                        0L,
                        period,
                        TimeUnit.MILLISECONDS
                    )
                }
                refreshHostSensors()
            }

            fun handleFramedMessages(ch: ChannelState, incoming: ByteArray, onMessage: (String) -> Unit) {
                ch.rxBuf.write(incoming)
                var bytes = ch.rxBuf.toByteArray()
                var pos = 0
                while (bytes.size - pos >= 4) {
                    val lenStr = String(bytes, pos, 4, Charsets.US_ASCII)
                    val frameLen = lenStr.toIntOrNull(16) ?: -1
                    if (frameLen < 0) {
                        pos = bytes.size
                        break
                    }
                    if (bytes.size - pos < 4 + frameLen) break
                    val msg = String(bytes, pos + 4, frameLen, Charsets.ISO_8859_1)
                    pos += 4 + frameLen
                    onMessage(msg)
                }
                ch.rxBuf.reset()
                if (pos < bytes.size) {
                    ch.rxBuf.write(bytes, pos, bytes.size - pos)
                }
            }

            val hdrBuf = ByteArray(6)
            try {
                while (running) {
                    input.readFully(hdrBuf)
                    val hdrStr = String(hdrBuf, Charsets.US_ASCII)
                    val chId = hdrStr.substring(0, 2).toIntOrNull(16) ?: continue
                    val size = hdrStr.substring(2, 6).toIntOrNull(16) ?: continue
                    if (size <= 0 || size > 65535) continue
                    val payload = ByteArray(size)
                    input.readFully(payload)

                    if (chId == 0) {
                        val ctrl = String(payload, Charsets.ISO_8859_1)
                        when {
                            ctrl.startsWith("connect:") -> {
                                val rest = ctrl.substring(8)
                                val sep = rest.lastIndexOf(':')
                                if (sep > 0) {
                                    val service = rest.substring(0, sep)
                                    val cid = rest.substring(sep + 1).toIntOrNull(16) ?: -1
                                    if (cid > 0 && service in setOf("gsm", "sensors", "boot-properties", "hw-control")) {
                                        channels[cid] = ChannelState(cid, service)
                                        sendControl(String.format(Locale.US, "ok:connect:%02x", cid))
                                    } else if (cid > 0) {
                                        sendControl(String.format(Locale.US, "ko:connect:%02x:unknown service", cid))
                                    }
                                }
                            }
                            ctrl.startsWith("disconnect:") -> {
                                val cid = ctrl.substring(11).take(2).toIntOrNull(16) ?: -1
                                channels.remove(cid)?.let { removed ->
                                    removed.timerTask?.cancel(false)
                                    removed.timerTask = null
                                    refreshHostSensors()
                                }
                            }
                            else -> sendControl("ko:unknown command")
                        }
                    } else {
                        val ch = channels[chId] ?: continue
                        when (ch.service) {
                            "boot-properties" -> {
                                handleFramedMessages(ch, payload) { msg ->
                                    if (msg == "list") {
                                        channels.remove(ch.id)
                                        sendControl(String.format(Locale.US, "disconnect:%02x", ch.id))
                                    }
                                }
                            }
                            "hw-control" -> {
                                handleFramedMessages(ch, payload) { _ ->
                                    // Accept LCD backlight / LED / vibrator control queries silently
                                }
                            }
                            "gsm" -> {
                                ch.rxBuf.write(payload)
                                val bytes = ch.rxBuf.toByteArray()
                                var start = 0
                                for (i in bytes.indices) {
                                    val b = bytes[i].toInt() and 0xFF
                                    if (b == '\r'.code || b == '\n'.code) {
                                        if (i > start) {
                                            val line = String(bytes, start, i - start, Charsets.ISO_8859_1).trim()
                                            if (line.isNotEmpty()) {
                                                val resp = modem.handle(line)
                                                if (resp != null) {
                                                    sendPacket(ch.id, (resp + "\r").toByteArray(Charsets.ISO_8859_1))
                                                }
                                            }
                                        }
                                        start = i + 1
                                    }
                                }
                                ch.rxBuf.reset()
                                if (start < bytes.size) {
                                    ch.rxBuf.write(bytes, start, bytes.size - start)
                                }
                            }
                            "sensors" -> {
                                handleFramedMessages(ch, payload) { msg ->
                                    when {
                                        msg == "list-sensors" -> {
                                            sendPacket(
                                                ch.id,
                                                SENSOR_LIST_MASK.toString().toByteArray(Charsets.US_ASCII),
                                                framed = true
                                            )
                                        }
                                        msg == "wake" -> {
                                            sendPacket(ch.id, "wake".toByteArray(Charsets.US_ASCII), framed = true)
                                        }
                                        msg.startsWith("set-delay:") -> {
                                            val ms = msg.substring(10).trim().toLongOrNull()
                                            if (ms != null) {
                                                ch.delayMs = ms.coerceIn(20L, 1000L)
                                                rescheduleSensorTimer(ch)
                                            }
                                        }
                                        msg.startsWith("set:") -> {
                                            val rest = msg.substring(4)
                                            val sep = rest.indexOf(':')
                                            if (sep > 0) {
                                                val name = rest.substring(0, sep)
                                                val enabled = rest.substring(sep + 1).startsWith("1")
                                                val bit = when (name) {
                                                    "acceleration" -> BIT_ACCEL
                                                    "magnetic-field" -> BIT_MAG
                                                    "orientation" -> BIT_ORIENT
                                                    "temperature" -> BIT_TEMP
                                                    else -> 0
                                                }
                                                if (bit != 0) {
                                                    ch.enabledMask = if (enabled) {
                                                        ch.enabledMask or bit
                                                    } else {
                                                        ch.enabledMask and bit.inv()
                                                    }
                                                    rescheduleSensorTimer(ch)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                channels.values.forEach { it.timerTask?.cancel(false) }
                channels.clear()
                unregisterHostSensors()
            }
        }
    }
}
