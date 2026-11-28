package id.neotica.minidroid

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

object ImageInstaller {
    private const val ASSET_DIR = "images"
    private const val KEYMAP_DIR = "keymaps"
    private const val PREFS = "minidroid_prefs"
    private const val KEY_SDCARD_MB = "sdcard_size_mb"

    private val romVersion: Int get() = GuestProfile.romVersion

    fun imagesDir(context: Context): File = File(context.filesDir, "images").apply { mkdirs() }
    fun keymapsDir(context: Context): File = File(context.filesDir, "keymaps").apply { mkdirs() }
    private fun romVersionFile(context: Context) = File(imagesDir(context), ".rom_version")

    fun getConfiguredSdCardMb(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SDCARD_MB, Fat32Formatter.DEFAULT_SIZE_MB)
            .coerceIn(256, 2048)
    }

    fun getCurrentSdCardSizeMb(context: Context): Int {
        val sd = sdcardFile(context)
        if (sd.exists() && sd.length() > 0L) {
            return ((sd.length() + (512L * 1024L)) / (1024L * 1024L)).toInt().coerceAtLeast(1)
        }
        return getConfiguredSdCardMb(context)
    }

    fun recreateSdCard(context: Context, sizeMb: Int) {
        val clamped = sizeMb.coerceIn(256, 2048)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SDCARD_MB, clamped)
            .apply()
        Fat32Formatter.createFat32Image(sdcardFile(context), clamped)
    }

    fun defaultSharedDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Shared").apply { mkdirs() }
    }

    fun kernelFile(context: Context) = File(imagesDir(context), "kernel-qemu")
    fun ramdiskFile(context: Context) = File(imagesDir(context), "ramdisk.img")
    fun systemFile(context: Context) = File(imagesDir(context), "system.img")
    fun userdataFile(context: Context) = File(imagesDir(context), "userdata.img")
    fun cacheFile(context: Context) = File(imagesDir(context), "cache.img")
    fun sdcardFile(context: Context) = File(imagesDir(context), "sdcard.img")
    fun enUsKeymapFile(context: Context) = File(keymapsDir(context), "en-us")
    fun bootLogFile(context: Context) = File(context.filesDir, "boot.log")

    fun ensureImagesExtracted(context: Context, onProgress: (String) -> Unit) {
        val dir = imagesDir(context)
        dir.mkdirs()
        keymapsDir(context)

        copyAssetIfMissing(context, "$KEYMAP_DIR/en-us", enUsKeymapFile(context)) {
            onProgress("Extracting QEMU keymap...")
        }
        copyAssetIfMissing(context, "$ASSET_DIR/kernel-qemu", kernelFile(context)) {
            onProgress("Extracting Linux 2.6.29 kernel...")
        }
        copyAssetIfMissing(context, "$ASSET_DIR/ramdisk.img", ramdiskFile(context)) {
            onProgress("Extracting ramdisk...")
        }
        val storedRomVersion = try {
            romVersionFile(context).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
        val sysFile = systemFile(context)
        if (storedRomVersion < romVersion && sysFile.exists()) {
            sysFile.delete()
        }
        gunzipAssetIfMissing(context, "$ASSET_DIR/system.img.z", sysFile) {
            onProgress("Extracting ${GuestProfile.osName} system image...")
        }
        if (storedRomVersion < romVersion) {
            try {
                romVersionFile(context).writeText(romVersion.toString())
            } catch (_: Exception) {
            }
        }
        gunzipAssetIfMissing(context, "$ASSET_DIR/userdata.img.z", userdataFile(context)) {
            onProgress("Initializing 512 MB internal storage...")
        }
        val sd = sdcardFile(context)
        if (!sd.exists() || sd.length() == 0L) {
            val sizeMb = getConfiguredSdCardMb(context)
            onProgress("Creating ${sizeMb} MB virtual SD card...")
            Fat32Formatter.createFat32Image(sd, sizeMb)
        }
        defaultSharedDir(context)
    }

    fun resetUserdata(context: Context) {
        userdataFile(context).delete()
        cacheFile(context).delete()
        gunzipAssetIfMissing(context, "$ASSET_DIR/userdata.img.z", userdataFile(context)) {}
    }

    private fun copyAssetIfMissing(
        context: Context,
        assetPath: String,
        target: File,
        onStart: () -> Unit
    ) {
        if (target.exists() && target.length() > 0) return
        onStart()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun gunzipAssetIfMissing(
        context: Context,
        assetPath: String,
        target: File,
        onStart: () -> Unit
    ) {
        if (target.exists() && target.length() > 0) return
        onStart()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        GZIPInputStream(context.assets.open(assetPath), 64 * 1024).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
