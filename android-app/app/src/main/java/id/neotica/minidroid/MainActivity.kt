package id.neotica.minidroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var emulatorView: EmulatorView
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121417"))
        }

        // Top toolbar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1E24"))
            setPadding(20, 16, 16, 16)
        }

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "MiniDroid 1.6"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#9CCC65"))
        })
        statusText = TextView(this).apply {
            text = "Starting..."
            textSize = 11f
            setTextColor(Color.parseColor("#9AA4B2"))
        }
        titleCol.addView(statusText)

        topBar.addView(titleCol, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(
            makeTopButton("APKs / Files", "#4E7A28") {
                startActivity(Intent(this, SharedStorageActivity::class.java))
            },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 10
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

        // Bottom classic Android 1.6 hardware keys bar
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1E24"))
            setPadding(12, 12, 12, 18)
        }
        navBar.addView(makeNavButton("← Back") { vncClient?.tapKey(0xFF1B) }, navParams())   // Esc -> KEY_BACK
        navBar.addView(makeNavButton("⌂ Home") { vncClient?.tapKey(0xFF50) }, navParams())   // Home -> KEY_HOME
        navBar.addView(makeNavButton("☰ Menu") { vncClient?.tapKey(0xFFBE) }, navParams())   // F1 -> MENU (also unlocks!)
        navBar.addView(makeNavButton("⌕ Search") { vncClient?.tapKey(0xFFC2) }, navParams()) // F5 -> KEY_SEARCH
        navBar.addView(makeNavButton("⌨ Keys") { toggleSoftKeyboard() }, navParams())
        root.addView(navBar)

        setContentView(root)

        EmulatorService.addStateListener(stateListener)
        if (!EmulatorService.isRunning) {
            EmulatorService.startVm(this)
        } else {
            connectVnc()
        }
        startAdbStatusPoller()
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

    private fun showPowerMenu() {
        val items = arrayOf(
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
                    0 -> showLogcatDialog()
                    1 -> vncClient?.tapKey(0xFFC4) // F7 -> KEY_POWER
                    2 -> vncClient?.tapKey(0xFFC6) // F9 -> KEY_VOLUMEUP
                    3 -> vncClient?.tapKey(0xFFC5) // F8 -> KEY_VOLUMEDOWN
                    4 -> vncClient?.tapKey(0xFFC3) // F6 -> KEY_CAMERA
                    5 -> {
                        disconnectVnc()
                        EmulatorService.restartVm(this)
                    }
                    6 -> showLogsDialog()
                    7 -> confirmFactoryReset()
                    8 -> {
                        disconnectVnc()
                        EmulatorService.stopVm(this)
                        finish()
                    }
                }
            }
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
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(bgHex))
        setPadding(20, 10, 20, 10)
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun makeNavButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11.5f
        setTextColor(Color.parseColor("#E6EDF3"))
        setBackgroundColor(Color.parseColor("#252B34"))
        setPadding(4, 18, 4, 18)
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }
}
