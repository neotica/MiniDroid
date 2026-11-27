package com.laetuz.minidroid

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
            "Power Button (Wake / Sleep)",
            "Reboot Android 1.6",
            "View Boot / QEMU Logs",
            "Factory Reset Userdata",
            "Shut Down Emulator"
        )
        AlertDialog.Builder(this)
            .setTitle("MiniDroid 1.6 Controls")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> vncClient?.tapKey(0xFFC4) // F7 -> KEY_POWER
                    1 -> {
                        disconnectVnc()
                        EmulatorService.restartVm(this)
                    }
                    2 -> showLogsDialog()
                    3 -> confirmFactoryReset()
                    4 -> {
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

    private fun showLogsDialog() {
        val bootLog = ImageInstaller.bootLogFile(this).takeIf { it.exists() }?.readText()?.takeLast(4000)
            ?: "(no kernel log yet)"
        val qemuLog = java.io.File(filesDir, "qemu.log").takeIf { it.exists() }?.readText()?.takeLast(1500)
            ?: ""
        val tv = TextView(this).apply {
            text = "=== QEMU ===\n$qemuLog\n\n=== KERNEL / SERIAL ===\n$bootLog"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(24, 20, 24, 20)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("MiniDroid Logs")
            .setView(scroll)
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
