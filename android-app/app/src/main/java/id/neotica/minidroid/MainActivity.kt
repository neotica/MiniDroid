package id.neotica.minidroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "minidroid_prefs"
        private const val KEY_FULLSCREEN = "fullscreen_mode"
    }

    private lateinit var topBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var emulatorView: EmulatorView
    private lateinit var primaryNavBar: LinearLayout
    private lateinit var secondaryNavBar: LinearLayout
    private lateinit var exitFullscreenBtn: Button
    private var isFullscreen = false
    private var vncClient: VncClient? = null
    private var adbPollerRunning = false

    private val stateListener: (String, Boolean) -> Unit = { state, running ->
        runOnUiThread {
            statusText.text = state
            if (running && vncClient == null) {
                connectVnc()
            } else if (!running) {
                disconnectVnc()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPortraitLock(QemudServer.isPortraitLocked(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121417"))
        }

        // Top toolbar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1E24"))
            setPadding(16, 14, 12, 14)
        }

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "MiniDroid 1.6"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#9CCC65"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        statusText = TextView(this).apply {
            text = "Starting..."
            textSize = 10.5f
            setTextColor(Color.parseColor("#9AA4B2"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(statusText)

        topBar.addView(
            titleCol,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
        )
        topBar.addView(
            makeTopButton("Logcat", "#334155") {
                showLogcatDialog()
            },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8
            }
        )
        topBar.addView(
            makeTopButton("APKs / Files", "#4E7A28") {
                startActivity(Intent(this, SharedStorageActivity::class.java))
            },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8
            }
        )
        topBar.addView(makeTopButton("⋮", "#2B313B") { showPowerMenu() })
        root.addView(topBar)

        // Center Android 1.6 live screen
        emulatorView = EmulatorView(this)
        root.addView(
            emulatorView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = 8
                bottomMargin = 8
            }
        )

        // Bottom controls container:
        // Row 1 (right under the emulator screen): Back, Home, Menu (+ Exit Fullscreen when fullscreen)
        // Row 2 (below Row 1): Search, Keys, Fullscreen toggle
        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1E24"))
        }

        primaryNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 10, 12, 6)
        }
        primaryNavBar.addView(makeNavButton("← Back") { vncClient?.tapKey(0xFF1B) }, navParams())  // Esc -> KEY_BACK
        primaryNavBar.addView(makeNavButton("⌂ Home") { vncClient?.tapKey(0xFF50) }, navParams())  // Home -> KEY_HOME
        primaryNavBar.addView(makeNavButton("☰ Menu") { vncClient?.tapKey(0xFFBE) }, navParams())  // F1 -> MENU (also unlocks!)
        exitFullscreenBtn = makeNavButton("⛶ Exit", "#334155") {
            setFullscreen(false)
        }.apply {
            visibility = View.GONE
            setPadding(18, 16, 18, 16)
        }
        primaryNavBar.addView(
            exitFullscreenBtn,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 4
                marginEnd = 4
            }
        )
        bottomContainer.addView(primaryNavBar)

        secondaryNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 4, 12, 16)
        }
        secondaryNavBar.addView(makeNavButton("⌕ Search", "#20262E") { vncClient?.tapKey(0xFFC2) }, navParams()) // F5 -> KEY_SEARCH
        secondaryNavBar.addView(makeNavButton("⌨ Keys", "#20262E") { toggleSoftKeyboard() }, navParams())
        secondaryNavBar.addView(makeNavButton("⛶ Fullscreen", "#20262E") { setFullscreen(true) }, navParams())
        bottomContainer.addView(secondaryNavBar)

        root.addView(bottomContainer)

        setContentView(root)

        val savedFullscreen = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FULLSCREEN, false)
        setFullscreen(savedFullscreen, persist = false)

        EmulatorService.addStateListener(stateListener)
        if (!EmulatorService.isRunning) {
            EmulatorService.startVm(this)
        } else {
            connectVnc()
        }
        startAdbStatusPoller()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) {
            applySystemBars(true)
        }
    }

    override fun onDestroy() {
        adbPollerRunning = false
        EmulatorService.removeStateListener(stateListener)
        disconnectVnc()
        super.onDestroy()
    }

    private fun connectVnc() {
        disconnectVnc()
        val client = VncClient(
            onConnected = { w, h ->
                runOnUiThread {
                    statusText.text = "Android 1.6 (${w}×${h}) • Tap Menu to unlock"
                }
            },
            onFrame = { pixels, w, h ->
                emulatorView.updateFrame(pixels, w, h)
            },
            onDisconnected = { reason ->
                runOnUiThread {
                    if (EmulatorService.isRunning) {
                        statusText.text = "Waiting for display ($reason)"
                    }
                }
            }
        )
        vncClient = client
        emulatorView.vncClient = client
        client.start()
    }

    private fun disconnectVnc() {
        vncClient?.stop()
        vncClient = null
        emulatorView.vncClient = null
    }

    private fun startAdbStatusPoller() {
        adbPollerRunning = true
        val adb = AdbClient()
        thread(name = "AdbStatusPoll", isDaemon = true) {
            while (adbPollerRunning) {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
                if (EmulatorService.isRunning && adb.isReady()) {
                    runOnUiThread {
                        if (statusText.text.contains("Waiting") || statusText.text.contains("Booting")) {
                            statusText.text = "Android 1.6 • ADB Ready"
                        }
                    }
                }
            }
        }
    }

    private fun toggleSoftKeyboard() {
        emulatorView.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun setFullscreen(enabled: Boolean, persist: Boolean = true) {
        isFullscreen = enabled
        if (persist) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FULLSCREEN, enabled)
                .apply()
        }
        topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        secondaryNavBar.visibility = if (enabled) View.GONE else View.VISIBLE
        exitFullscreenBtn.visibility = if (enabled) View.VISIBLE else View.GONE

        val lp = emulatorView.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.topMargin = if (enabled) 0 else 8
            lp.bottomMargin = if (enabled) 0 else 8
            emulatorView.layoutParams = lp
        }
        if (enabled) {
            primaryNavBar.setPadding(8, 6, 8, 10)
        } else {
            primaryNavBar.setPadding(12, 10, 12, 6)
        }
        applySystemBars(enabled)
    }

    private fun applySystemBars(hide: Boolean) {
        val attrs = window.attributes
        attrs.layoutInDisplayCutoutMode = if (hide) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        window.attributes = attrs

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyPortraitLock(locked: Boolean) {
        requestedOrientation = if (locked) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showPowerMenu() {
        val portraitLocked = QemudServer.isPortraitLocked(this)
        val sdMb = ImageInstaller.getCurrentSdCardSizeMb(this)
        val items = arrayOf(
            if (isFullscreen) "Fullscreen: ON (Exit Fullscreen)" else "Fullscreen: OFF (Enter Fullscreen)",
            if (portraitLocked) "Auto-Rotation: OFF (Portrait Locked)" else "Auto-Rotation: ON (Lock Portrait)",
            "Storage / SD Card Size ($sdMb MB)",
            "Logcat (AndroidRuntime)",
            "Power Button (Wake / Sleep)",
            "Guest Volume Up",
            "Guest Volume Down",
            "Camera Shutter Button",
            "Reboot Android 1.6",
            "View Boot / QEMU Logs",
            "Factory Reset Userdata",
            "Shut Down Emulator"
        )
        AlertDialog.Builder(this)
            .setTitle("MiniDroid 1.6 Controls")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setFullscreen(!isFullscreen)
                    1 -> {
                        val nextLocked = !portraitLocked
                        QemudServer.setPortraitLocked(this, nextLocked)
                        applyPortraitLock(nextLocked)
                        Toast.makeText(
                            this,
                            if (nextLocked) "Portrait locked (auto-rotation OFF)" else "Auto-rotation ON",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> showStorageDialog()
                    3 -> showLogcatDialog()
                    4 -> vncClient?.tapKey(0xFFC4) // F7 -> KEY_POWER
                    5 -> vncClient?.tapKey(0xFFC6) // F9 -> KEY_VOLUMEUP
                    6 -> vncClient?.tapKey(0xFFC5) // F8 -> KEY_VOLUMEDOWN
                    7 -> vncClient?.tapKey(0xFFC3) // F6 -> KEY_CAMERA
                    8 -> {
                        disconnectVnc()
                        EmulatorService.restartVm(this)
                    }
                    9 -> showLogsDialog()
                    10 -> confirmFactoryReset()
                    11 -> {
                        disconnectVnc()
                        EmulatorService.stopVm(this)
                        finish()
                    }
                }
            }
            .show()
    }

    private fun showStorageDialog() {
        val currentSdMb = ImageInstaller.getCurrentSdCardSizeMb(this)
        val sizes = Fat32Formatter.SUPPORTED_SIZES_MB
        val labels = sizes.map { mb ->
            val nice = if (mb >= 1024) "${mb / 1024} GB ($mb MB)" else "$mb MB"
            if (mb == currentSdMb) "$nice  (current)" else nice
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Virtual SD Card Size (Internal /data: 512 MB)")
            .setItems(labels) { _, which ->
                val chosenMb = sizes[which]
                val chosenLabel = if (chosenMb >= 1024) "${chosenMb / 1024} GB" else "$chosenMb MB"
                AlertDialog.Builder(this)
                    .setTitle("Resize SD Card to $chosenLabel?")
                    .setMessage(
                        "Creates a fresh $chosenLabel FAT32 virtual SD card and reboots Android 1.6.\n\n" +
                            "Internal storage (/data, 512 MB), installed apps, and your Host Shared Folder are kept. " +
                            "Existing files stored inside /sdcard will be wiped."
                    )
                    .setPositiveButton("Resize & Reboot") { _, _ ->
                        disconnectVnc()
                        EmulatorService.stopVm(this)
                        statusText.text = "Creating $chosenLabel SD card..."
                        thread(name = "ResizeSdCard") {
                            var waited = 0
                            while (EmulatorService.isRunning && waited < 2500) {
                                Thread.sleep(100)
                                waited += 100
                            }
                            Thread.sleep(300)
                            ImageInstaller.recreateSdCard(this, chosenMb)
                            runOnUiThread {
                                Toast.makeText(this, "SD card resized to $chosenLabel", Toast.LENGTH_SHORT).show()
                            }
                            EmulatorService.startVm(this)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmFactoryReset() {
        AlertDialog.Builder(this)
            .setTitle("Factory Reset Android 1.6?")
            .setMessage("This wipes installed apps and data inside Android 1.6 (your Host Shared Folder and SD card are kept).")
            .setPositiveButton("Reset") { _, _ ->
                disconnectVnc()
                EmulatorService.stopVm(this)
                thread {
                    Thread.sleep(800)
                    ImageInstaller.resetUserdata(this)
                    EmulatorService.startVm(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogcatDialog() {
        val adb = AdbClient()
        var currentCmd = "logcat -d -s AndroidRuntime"
        var currentLabel = "AndroidRuntime"
        var lastOutput = ""

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 8)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        val tv = TextView(this).apply {
            text = "Running adb $currentCmd..."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.parseColor("#121417"))
            setTextColor(Color.parseColor("#E6EDF3"))
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
        }

        fun fetchLog(cmd: String, label: String) {
            currentCmd = cmd
            currentLabel = label
            tv.text = "Running $cmd..."
            thread(name = "AdbLogcat", isDaemon = true) {
                val output = try {
                    val raw = adb.shell(cmd, timeoutMs = 15_000).trim()
                    if (raw.isEmpty()) "(no $label entries yet)" else raw.takeLast(20_000)
                } catch (e: Exception) {
                    "ADB not ready or failed: ${e.message}"
                }
                lastOutput = output
                runOnUiThread {
                    tv.text = output
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }

        fun smallBtn(label: String, bg: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(bg))
            setPadding(12, 8, 12, 8)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }

        val lp = {
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
        }
        btnRow.addView(smallBtn("Runtime", "#4E7A28") {
            fetchLog("logcat -d -s AndroidRuntime", "AndroidRuntime")
        }, lp())
        btnRow.addView(smallBtn("Errors", "#8D3B2F") {
            fetchLog("logcat -d *:E", "Errors (*:E)")
        }, lp())
        btnRow.addView(smallBtn("All", "#2B313B") {
            fetchLog("logcat -d", "All")
        }, lp())
        btnRow.addView(smallBtn("Clear", "#2B313B") {
            thread(name = "AdbLogcatClear", isDaemon = true) {
                try {
                    adb.shell("logcat -c", timeoutMs = 10_000)
                } catch (_: Exception) {
                }
                runOnUiThread { fetchLog(currentCmd, currentLabel) }
            }
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        container.addView(btnRow)
        container.addView(
            scroll,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55f).toInt())
        )

        AlertDialog.Builder(this)
            .setTitle("Logcat — Android 1.6")
            .setView(container)
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard("MiniDroid Logcat", lastOutput.ifEmpty { tv.text.toString() })
            }
            .setNegativeButton("Refresh", null)
            .setPositiveButton("Close", null)
            .show()
            .also { dialog ->
                // Keep dialog open when tapping Refresh
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                    fetchLog(currentCmd, currentLabel)
                }
            }

        fetchLog(currentCmd, currentLabel)
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun showLogsDialog() {
        val bootLog = ImageInstaller.bootLogFile(this).takeIf { it.exists() }?.readText()?.takeLast(4000)
            ?: "(no kernel log yet)"
        val qemuLog = java.io.File(filesDir, "qemu.log").takeIf { it.exists() }?.readText()?.takeLast(1500)
            ?: ""
        val combined = "=== QEMU ===\n$qemuLog\n\n=== KERNEL / SERIAL ===\n$bootLog"
        val tv = TextView(this).apply {
            text = combined
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 20, 24, 20)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("MiniDroid Logs")
            .setView(scroll)
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard("MiniDroid Boot Logs", combined)
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun navParams() = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = 4
        marginEnd = 4
    }

    private fun makeTopButton(label: String, bgHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11.5f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(bgHex))
        setPadding(14, 10, 14, 10)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun makeNavButton(
        label: String,
        bgHex: String = "#252B34",
        onClick: () -> Unit
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.parseColor("#E6EDF3"))
        setBackgroundColor(Color.parseColor(bgHex))
        setPadding(8, 16, 8, 16)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }
}
