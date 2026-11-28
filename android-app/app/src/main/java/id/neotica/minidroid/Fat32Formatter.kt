package id.neotica.minidroid

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin sparse FAT32 disk image formatter for Goldfish MMC (/sdcard).
 * Creates valid 256 MB – 2048 MB FAT32 SD card images in milliseconds without
 * needing external tools or bundling huge pre-built images in the APK.
 */
object Fat32Formatter {

    val SUPPORTED_SIZES_MB = intArrayOf(256, 512, 1024, 2048)
    const val DEFAULT_SIZE_MB = 512

    fun createFat32Image(target: File, sizeMb: Int, volumeLabel: String = "SDCARD") {
        val clampedMb = sizeMb.coerceIn(256, 2048)
        val totalBytes = clampedMb.toLong() * 1024L * 1024L
        val bytesPerSector = 512
        val totalSectors = (totalBytes / bytesPerSector).toInt()
        val reservedSectors = 32
        val numFats = 2
        // FAT32 requires >= 65525 data clusters: use 4 sec/cluster for <= 260 MB, 8 sec/cluster for >= 512 MB
        val sectorsPerCluster = if (clampedMb <= 260) 4 else 8

        val tmp1 = totalSectors - reservedSectors
        val tmp2 = (256 * sectorsPerCluster + numFats) / 2
        val fatSizeSectors = (tmp1 + tmp2 - 1) / tmp2
        val dataStartSector = reservedSectors + numFats * fatSizeSectors
        val clusterCount = (totalSectors - dataStartSector) / sectorsPerCluster

        val labelBytes = volumeLabel.uppercase()
            .take(11)
            .padEnd(11, ' ')
            .toByteArray(Charsets.US_ASCII)

        // 1. Boot sector (sector 0 & backup sector 6)
        val boot = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        boot.put(0, 0xEB.toByte())
        boot.put(1, 0x58.toByte())
        boot.put(2, 0x90.toByte())
        boot.position(3)
        boot.put("MSDOS5.0".toByteArray(Charsets.US_ASCII))
        boot.putShort(0x0B, bytesPerSector.toShort())
        boot.put(0x0D, sectorsPerCluster.toByte())
        boot.putShort(0x0E, reservedSectors.toShort())
        boot.put(0x10, numFats.toByte())
        boot.putShort(0x11, 0) // rootEntryCount = 0 for FAT32
        boot.putShort(0x13, 0) // totalSectors16 = 0 for FAT32
        boot.put(0x15, 0xF8.toByte()) // fixed/removable media descriptor
        boot.putShort(0x16, 0) // fatSize16 = 0 for FAT32
        boot.putShort(0x18, 63) // sectorsPerTrack
        boot.putShort(0x1A, 255) // numHeads
        boot.putInt(0x1C, 0) // hiddenSectors
        boot.putInt(0x20, totalSectors)
        boot.putInt(0x24, fatSizeSectors)
        boot.putShort(0x28, 0) // extFlags
        boot.putShort(0x2A, 0) // fsVersion
        boot.putInt(0x2C, 2) // rootCluster = 2
        boot.putShort(0x30, 1) // fsInfoSector = 1
        boot.putShort(0x32, 6) // backupBootSector = 6
        boot.put(0x40, 0x80.toByte()) // driveNumber
        boot.put(0x42, 0x29.toByte()) // extended boot signature
        boot.putInt(0x43, 0x4D494E49) // volumeId ("MINI")
        boot.position(0x47)
        boot.put(labelBytes)
        boot.position(0x52)
        boot.put("FAT32   ".toByteArray(Charsets.US_ASCII))
        boot.put(0x1FE, 0x55.toByte())
        boot.put(0x1FF, 0xAA.toByte())

        // 2. FSInfo sector (sector 1 & backup sector 7)
        val fsInfo = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        fsInfo.putInt(0x000, 0x41615252) // LeadSig "RRaA"
        fsInfo.putInt(0x1E4, 0x61417272) // StrucSig "rrAa"
        fsInfo.putInt(0x1E8, (clusterCount - 1).coerceAtLeast(0)) // Free_Count
        fsInfo.putInt(0x1EC, 3) // Nxt_Free
        fsInfo.put(0x1FE, 0x55.toByte())
        fsInfo.put(0x1FF, 0xAA.toByte())

        // 3. Initial FAT sector (entries 0, 1, and cluster 2 = EOC for root dir)
        val fatSector = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        fatSector.putInt(0, 0x0FFFFFF8)
        fatSector.putInt(4, 0x0FFFFFFF)
        fatSector.putInt(8, 0x0FFFFFFF)

        // 4. Root directory volume label entry
        val rootDir = ByteArray(bytesPerSector)
        System.arraycopy(labelBytes, 0, rootDir, 0, 11)
        rootDir[11] = 0x08 // ATTR_VOLUME_ID

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        if (tmp.exists()) tmp.delete()

        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(totalBytes)
            raf.seek(0L)
            raf.write(boot.array())
            raf.seek(1L * bytesPerSector)
            raf.write(fsInfo.array())
            raf.seek(6L * bytesPerSector)
            raf.write(boot.array())
            raf.seek(7L * bytesPerSector)
            raf.write(fsInfo.array())

            raf.seek(reservedSectors.toLong() * bytesPerSector)
            raf.write(fatSector.array())
            raf.seek((reservedSectors + fatSizeSectors).toLong() * bytesPerSector)
            raf.write(fatSector.array())

            raf.seek(dataStartSector.toLong() * bytesPerSector)
            raf.write(rootDir)
            raf.fd.sync()
        }

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
