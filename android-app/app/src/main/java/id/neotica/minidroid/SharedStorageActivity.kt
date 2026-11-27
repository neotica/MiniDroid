package id.neotica.minidroid

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Shared Storage & Live APK Manager.
 * Lets the user choose a host shared folder (SAF) or use the default Shared folder,
 * import & one-tap install APKs into Android 1.6 via local ADB, and push/pull files
 * live between the host phone and Android 1.6's /sdcard/Shared directory.
 */
class SharedStorageActivity : AppCompatActivity() {

    private lateinit var folderLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var hostListContainer: LinearLayout
    private lateinit var guestListContainer: LinearLayout

    private val adb = AdbClient()

    private val pickSharedFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }
            getSharedPreferences(SharedHttpServer.PREFS, MODE_PRIVATE)
                .edit()
                .putString(SharedHttpServer.KEY_SHARED_TREE_URI, uri.toString())
                .apply()
            refreshAll()
        }
    }

    private val pickApkToInstall = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importAndInstallApk(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121417"))
            setPadding(28, 36, 28, 28)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = makeButton("← Back", "#262B33") { finish() }
        val title = TextView(this).apply {
            text = "Shared Storage & APKs"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(20, 0, 0, 0)
        }
        headerRow.addView(backBtn)
        headerRow.addView(title)
        root.addView(headerRow)

        folderLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 18, 0, 8)
        }
        root.addView(folderLabel)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actionsRow.addView(
            makeButton("Pick Folder", "#2C333D") { pickSharedFolder.launch(null) },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 10 }
        )
        actionsRow.addView(
            makeButton("Import & Install APK", "#558B2F") {
                pickApkToInstall.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.3f).apply { marginEnd = 10 }
        )
        actionsRow.addView(
            makeButton("Refresh", "#2C333D") { refreshAll() },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        root.addView(actionsRow)

        statusLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#9CCC65"))
            setPadding(0, 14, 0, 10)
            text = "Checking Android 1.6 ADB bridge..."
        }
        root.addView(statusLabel)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(makeSectionTitle("Host Phone Shared Folder (tap to Push / Install in 1.6)"))
        hostListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(hostListContainer)

        content.addView(makeSectionTitle("Android 1.6 /sdcard/Shared (tap to Pull to Host)"))
        guestListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(guestListContainer)

        val browserHint = TextView(this).apply {
            text = "Tip: You can also open Android 1.6 Browser and visit http://10.0.2.2:${SharedHttpServer.PORT}/ to browse & download files directly from your shared folder."
            textSize = 12f
            setTextColor(Color.parseColor("#7E8794"))
            setPadding(0, 24, 0, 16)
        }
        content.addView(browserHint)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        refreshAll()
    }

    private fun getSelectedTree(): DocumentFile? {
        val uriStr = getSharedPreferences(SharedHttpServer.PREFS, MODE_PRIVATE)
            .getString(SharedHttpServer.KEY_SHARED_TREE_URI, null) ?: return null
        return try {
            DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshAll() {
        val tree = getSelectedTree()
        folderLabel.text = if (tree != null && tree.isDirectory) {
            "Host folder: ${tree.name ?: tree.uri}"
        } else {
            "Host folder: ${ImageInstaller.defaultSharedDir(this).absolutePath}"
        }

        populateHostFiles(tree)
        refreshGuestFiles()
    }

    private fun populateHostFiles(tree: DocumentFile?) {
        hostListContainer.removeAllViews()
        var count = 0
        if (tree != null && tree.isDirectory) {
            val docs = tree.listFiles().filter { it.isFile }.sortedBy { it.name?.lowercase() ?: "" }
            for (doc in docs) {
                val name = doc.name ?: continue
                val kb = (doc.length() + 1023) / 1024
                val isApk = name.endsWith(".apk", ignoreCase = true)
                hostListContainer.addView(
                    makeFileRow(
                        title = name,
                        subtitle = "$kb KB • ${if (isApk) "Tap to Install or Push" else "Tap to Push to /sdcard/Shared"}"
                    ) {
                        onHostFileClicked(name, isApk) {
                            contentResolver.openInputStream(doc.uri)
                        }
                    }
                )
                count++
            }
        } else {
            val dir = ImageInstaller.defaultSharedDir(this)
            val files = (dir.listFiles() ?: emptyArray())
                .filter { it.isFile }
                .sortedBy { it.name.lowercase() }
            for (f in files) {
                val isApk = f.name.endsWith(".apk", ignoreCase = true)
                val kb = (f.length() + 1023) / 1024
                hostListContainer.addView(
                    makeFileRow(
                        title = f.name,
                        subtitle = "$kb KB • ${if (isApk) "Tap to Install or Push" else "Tap to Push to /sdcard/Shared"}"
                    ) {
                        onHostFileClicked(f.name, isApk) {
                            FileInputStream(f)
                        }
                    }
                )
                count++
            }
        }

        if (count == 0) {
            hostListContainer.addView(makeEmptyRow("No files in host shared folder yet. Use 'Import & Install APK' or 'Pick Folder'."))
        }
    }

    private fun refreshGuestFiles() {
        statusLabel.text = "Connecting to Android 1.6 ADB..."
        guestListContainer.removeAllViews()

        thread(name = "GuestList") {
            try {
                if (!adb.isReady()) {
                    runOnUiThread {
                        statusLabel.text = "Android 1.6 is still booting (ADB not ready yet). Tap Refresh in a few seconds."
                        guestListContainer.addView(makeEmptyRow("Waiting for Android 1.6 to finish booting..."))
                    }
                    return@thread
                }
                adb.shell("mkdir /sdcard/Shared")
                val entries = adb.listDir("/sdcard/Shared")
                runOnUiThread {
                    statusLabel.text = "Connected to Android 1.6 ADB • Live transfer ready"
                    guestListContainer.removeAllViews()
                    if (entries.isEmpty()) {
                        guestListContainer.addView(makeEmptyRow("/sdcard/Shared is empty."))
                    } else {
                        for (entry in entries) {
                            val kb = (entry.size + 1023) / 1024
                            val isApk = entry.name.endsWith(".apk", ignoreCase = true)
                            guestListContainer.addView(
                                makeFileRow(
                                    title = entry.name,
                                    subtitle = "$kb KB • Tap to Pull to Host${if (isApk) " or Install" else ""}"
                                ) {
                                    onGuestFileClicked(entry.name, isApk)
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusLabel.text = "ADB error: ${e.message}"
                    guestListContainer.addView(makeEmptyRow("Could not read /sdcard/Shared."))
                }
            }
        }
    }

    private fun onHostFileClicked(name: String, isApk: Boolean, openStream: () -> java.io.InputStream?) {
        val options = if (isApk) {
            arrayOf("Push & Install APK in Android 1.6", "Push to /sdcard/Shared only")
        } else {
            arrayOf("Push to /sdcard/Shared")
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                val install = isApk && which == 0
                pushHostFile(name, install, openStream)
            }
            .show()
    }

    private fun onGuestFileClicked(name: String, isApk: Boolean) {
        val options = if (isApk) {
            arrayOf("Pull to Host Shared Folder", "Install APK in Android 1.6", "Delete from /sdcard/Shared")
        } else {
            arrayOf("Pull to Host Shared Folder", "Delete from /sdcard/Shared")
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                when {
                    which == 0 -> pullGuestFile(name)
                    isApk && which == 1 -> installExistingGuestApk("/sdcard/Shared/$name")
                    else -> deleteGuestFile("/sdcard/Shared/$name")
                }
            }
            .show()
    }

    private fun importAndInstallApk(uri: Uri) {
        val rawName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.apk"
        val safeName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        statusLabel.text = "Copying $safeName to shared folder..."

        thread(name = "ImportApk") {
            try {
                // Save a copy in the host shared folder so the user can move it around freely
                saveCopyToHostShared(safeName) { contentResolver.openInputStream(uri) }

                runOnUiThread { statusLabel.text = "Pushing $safeName to Android 1.6..." }
                adb.shell("mkdir /sdcard/Shared")
                val remotePath = "/sdcard/Shared/$safeName"
                contentResolver.openInputStream(uri)?.use { input ->
                    adb.pushStream(input, remotePath)
                } ?: throw IllegalStateException("Cannot open selected APK")

                runOnUiThread { statusLabel.text = "Installing $safeName inside Android 1.6..." }
                val out = adb.shell("pm install -r \"$remotePath\"").trim()
                runOnUiThread {
                    statusLabel.text = "Install result: ${out.ifEmpty { "Done" }}"
                    Toast.makeText(this, out.ifEmpty { "APK installed!" }, Toast.LENGTH_LONG).show()
                    refreshAll()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusLabel.text = "Import/Install failed: ${e.message}"
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pushHostFile(name: String, installApk: Boolean, openStream: () -> java.io.InputStream?) {
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        statusLabel.text = "Pushing $safeName to /sdcard/Shared..."
        thread(name = "PushFile") {
            try {
                adb.shell("mkdir /sdcard/Shared")
                val remotePath = "/sdcard/Shared/$safeName"
                openStream()?.use { input ->
                    adb.pushStream(input, remotePath)
                } ?: throw IllegalStateException("Cannot read $name")

                val msg = if (installApk) {
                    runOnUiThread { statusLabel.text = "Installing $safeName in Android 1.6..." }
                    val res = adb.shell("pm install -r \"$remotePath\"").trim()
                    "Installed: $res"
                } else {
                    "Pushed to $remotePath"
                }
                runOnUiThread {
                    statusLabel.text = msg
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshGuestFiles()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusLabel.text = "Push failed: ${e.message}"
                    Toast.makeText(this, "Push failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pullGuestFile(name: String) {
        statusLabel.text = "Pulling $name from Android 1.6..."
        thread(name = "PullFile") {
            try {
                val tree = getSelectedTree()
                if (tree != null && tree.isDirectory) {
                    tree.findFile(name)?.delete()
                    val doc = tree.createFile("application/octet-stream", name)
                        ?: throw IllegalStateException("Cannot create $name in shared folder")
                    contentResolver.openOutputStream(doc.uri)?.use { out ->
                        adb.pullToStream("/sdcard/Shared/$name", out)
                    } ?: throw IllegalStateException("Cannot write $name")
                } else {
                    val outFile = File(ImageInstaller.defaultSharedDir(this), name)
                    FileOutputStream(outFile).use { out ->
                        adb.pullToStream("/sdcard/Shared/$name", out)
                    }
                }
                runOnUiThread {
                    statusLabel.text = "Pulled $name to Host Shared Folder"
                    Toast.makeText(this, "Saved $name to Host Shared Folder", Toast.LENGTH_SHORT).show()
                    refreshAll()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusLabel.text = "Pull failed: ${e.message}"
                    Toast.makeText(this, "Pull failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installExistingGuestApk(remotePath: String) {
        statusLabel.text = "Installing $remotePath..."
        thread(name = "InstallGuestApk") {
            try {
                val res = adb.shell("pm install -r \"$remotePath\"").trim()
                runOnUiThread {
                    statusLabel.text = "Install result: $res"
                    Toast.makeText(this, res, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusLabel.text = "Install failed: ${e.message}"
                }
            }
        }
    }

    private fun deleteGuestFile(remotePath: String) {
        thread(name = "DeleteGuestFile") {
            try {
                adb.shell("rm \"$remotePath\"")
                runOnUiThread { refreshGuestFiles() }
            } catch (e: Exception) {
                runOnUiThread { statusLabel.text = "Delete failed: ${e.message}" }
            }
        }
    }

    private fun saveCopyToHostShared(name: String, openSrc: () -> java.io.InputStream?) {
        val tree = getSelectedTree()
        if (tree != null && tree.isDirectory) {
            if (tree.findFile(name) == null) {
                val mime = if (name.endsWith(".apk", true)) "application/vnd.android.package-archive" else "application/octet-stream"
                val doc = tree.createFile(mime, name) ?: return
                openSrc()?.use { inp ->
                    contentResolver.openOutputStream(doc.uri)?.use { out -> inp.copyTo(out) }
                }
            }
        } else {
            val dst = File(ImageInstaller.defaultSharedDir(this), name)
            if (!dst.exists()) {
                openSrc()?.use { inp ->
                    FileOutputStream(dst).use { out -> inp.copyTo(out) }
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun makeSectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#CFD8DC"))
        setPadding(0, 20, 0, 8)
    }

    private fun makeEmptyRow(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#6D7684"))
        setPadding(12, 16, 12, 16)
    }

    private fun makeFileRow(title: String, subtitle: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1D2127"))
            setPadding(22, 16, 22, 16)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#8E99A8"))
            })
            setOnClickListener { onClick() }
        }

    private fun makeButton(label: String, bgHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(bgHex))
        setPadding(20, 12, 20, 12)
        setOnClickListener { onClick() }
    }
}
