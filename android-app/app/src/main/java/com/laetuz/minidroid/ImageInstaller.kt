package com.laetuz.minidroid

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

object ImageInstaller {
    private const val ASSET_DIR = "images"

    fun imagesDir(context: Context): File = File(context.filesDir, "images").apply { mkdirs() }

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
    fun bootLogFile(context: Context) = File(context.filesDir, "boot.log")

    fun ensureImagesExtracted(context: Context, onProgress: (String) -> Unit) {
        val dir = imagesDir(context)
        dir.mkdirs()

        copyAssetIfMissing(context, "$ASSET_DIR/kernel-qemu", kernelFile(context)) {
            onProgress("Extracting Linux 2.6.29 kernel...")
        }
        copyAssetIfMissing(context, "$ASSET_DIR/ramdisk.img", ramdiskFile(context)) {
            onProgress("Extracting ramdisk...")
        }
        gunzipAssetIfMissing(context, "$ASSET_DIR/system.img.z", systemFile(context)) {
            onProgress("Extracting Android 1.6 system image (first run)...")
        }
        gunzipAssetIfMissing(context, "$ASSET_DIR/userdata.img.z", userdataFile(context)) {
            onProgress("Initializing userdata partition...")
        }
        gunzipAssetIfMissing(context, "$ASSET_DIR/sdcard.img.z", sdcardFile(context)) {
            onProgress("Creating 64 MB virtual SD card...")
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
