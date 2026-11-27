package id.neotica.minidroid

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal pure-Kotlin ADB client speaking directly to Android 1.6's unauthenticated root adbd
 * forwarded over QEMU user networking (127.0.0.1:15555 -> guest 5555).
 */
class AdbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = EmulatorService.ADB_PORT
) {
    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD_DEFAULT = 4096
    }

    data class RemoteFile(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val mtimeSec: Long
    )

    private data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray
    )

    fun isReady(): Boolean {
        return try {
            withConnection(timeoutMs = 1500) { _, _, _ -> true }
        } catch (_: Exception) {
            false
        }
    }

    fun shell(command: String, timeoutMs: Int = 30_000): String {
        return withConnection(timeoutMs = timeoutMs) { input, output, _ ->
            val localId = 1
            val remoteId = openStream(input, output, localId, "shell:$command")
            val result = ByteArrayOutputStream()
            while (true) {
                val pkt = readPacket(input)
                when (pkt.command) {
                    A_WRTE -> {
                        result.write(pkt.data)
                        sendPacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    A_CLSE -> {
                        sendPacket(output, A_CLSE, localId, remoteId, ByteArray(0))
                        break
                    }
                }
            }
            result.toString(Charsets.UTF_8.name())
        }
    }

    fun pushStream(src: InputStream, remotePath: String, modeOctal: Int = 0b110_100_100 /* 0644 */) {
        withConnection(timeoutMs = 120_000) { input, output, maxPayload ->
            val localId = 1
            val remoteId = openStream(input, output, localId, "sync:")
            val stream = SyncStream(input, output, localId, remoteId, maxPayload)

            val spec = "$remotePath,${0x8000 or modeOctal}".toByteArray(Charsets.UTF_8)
            val sendHdr = ByteBuffer.allocate(8 + spec.size).order(ByteOrder.LITTLE_ENDIAN)
            sendHdr.put("SEND".toByteArray(Charsets.US_ASCII))
            sendHdr.putInt(spec.size)
            sendHdr.put(spec)
            stream.write(sendHdr.array())

            val chunkMax = minOf(maxPayload - 16, 3800)
            val buf = ByteArray(chunkMax)
            while (true) {
                val n = src.read(buf)
                if (n <= 0) break
                val dataPkt = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN)
                dataPkt.put("DATA".toByteArray(Charsets.US_ASCII))
                dataPkt.putInt(n)
                dataPkt.put(buf, 0, n)
                stream.write(dataPkt.array())
            }

            val donePkt = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            donePkt.put("DONE".toByteArray(Charsets.US_ASCII))
            donePkt.putInt((System.currentTimeMillis() / 1000L).toInt())
            stream.write(donePkt.array())

            val status = String(stream.readExact(4), Charsets.US_ASCII)
            val msgLen = readLeInt(stream.readExact(4))
            val msg = if (msgLen > 0) String(stream.readExact(msgLen), Charsets.UTF_8) else ""
            stream.close()
            if (status != "OKAY") {
                throw IOException("ADB push failed ($status): $msg")
            }
        }
    }

    fun pullToStream(remotePath: String, dst: OutputStream) {
        withConnection(timeoutMs = 60_000) { input, output, maxPayload ->
            val localId = 1
            val remoteId = openStream(input, output, localId, "sync:")
            val stream = SyncStream(input, output, localId, remoteId, maxPayload)

            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            req.put("RECV".toByteArray(Charsets.US_ASCII))
            req.putInt(pathBytes.size)
            req.put(pathBytes)
            stream.write(req.array())

            while (true) {
                val id = String(stream.readExact(4), Charsets.US_ASCII)
                val len = readLeInt(stream.readExact(4))
                when (id) {
                    "DATA" -> {
                        val chunk = stream.readExact(len)
                        dst.write(chunk)
                    }
                    "DONE" -> break
                    "FAIL" -> {
                        val err = if (len > 0) String(stream.readExact(len), Charsets.UTF_8) else "unknown"
                        throw IOException("ADB pull failed: $err")
                    }
                    else -> throw IOException("Unexpected sync response: $id")
                }
            }
            dst.flush()
            stream.close()
        }
    }

    fun listDir(remotePath: String): List<RemoteFile> {
        return withConnection(timeoutMs = 15_000) { input, output, maxPayload ->
            val localId = 1
            val remoteId = openStream(input, output, localId, "sync:")
            val stream = SyncStream(input, output, localId, remoteId, maxPayload)

            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            req.put("LIST".toByteArray(Charsets.US_ASCII))
            req.putInt(pathBytes.size)
            req.put(pathBytes)
            stream.write(req.array())

            val entries = mutableListOf<RemoteFile>()
            while (true) {
                val id = String(stream.readExact(4), Charsets.US_ASCII)
                val meta = ByteBuffer.wrap(stream.readExact(16)).order(ByteOrder.LITTLE_ENDIAN)
                val mode = meta.int
                val size = meta.int.toLong() and 0xFFFFFFFFL
                val mtime = meta.int.toLong() and 0xFFFFFFFFL
                val nameLen = meta.int
                when (id) {
                    "DENT" -> {
                        val name = String(stream.readExact(nameLen), Charsets.UTF_8)
                        if (name != "." && name != "..") {
                            val isDir = (mode and 0xF000) == 0x4000
                            entries.add(RemoteFile(name, size, isDir, mtime))
                        }
                    }
                    "DONE" -> break
                    else -> break
                }
            }
            stream.close()
            entries.sortedWith(compareBy<RemoteFile> { !it.isDir }.thenBy { it.name.lowercase() })
        }
    }

    private fun <T> withConnection(
        timeoutMs: Int,
        block: (DataInputStream, OutputStream, Int) -> T
    ): T {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = timeoutMs
            sock.tcpNoDelay = true
            val input = DataInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            sendPacket(output, A_CNXN, A_VERSION, MAX_PAYLOAD_DEFAULT, "host::\u0000".toByteArray(Charsets.UTF_8))
            val reply = readPacket(input)
            if (reply.command != A_CNXN) {
                throw IOException("Expected ADB CNXN, got 0x${reply.command.toString(16)}")
            }
            val maxPayload = minOf(MAX_PAYLOAD_DEFAULT, reply.arg1.coerceAtLeast(1024))
            return block(input, output, maxPayload)
        }
    }

    private fun openStream(
        input: DataInputStream,
        output: OutputStream,
        localId: Int,
        destination: String
    ): Int {
        val destBytes = (destination + "\u0000").toByteArray(Charsets.UTF_8)
        sendPacket(output, A_OPEN, localId, 0, destBytes)
        while (true) {
            val pkt = readPacket(input)
            if (pkt.command == A_OKAY && pkt.arg1 == localId) {
                return pkt.arg0
            } else if (pkt.command == A_CLSE) {
                throw IOException("ADB rejected stream: $destination")
            }
        }
    }

    private fun sendPacket(output: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(cmd)
        hdr.putInt(arg0)
        hdr.putInt(arg1)
        hdr.putInt(data.size)
        hdr.putInt(sum)
        hdr.putInt(cmd xor -1)
        output.write(hdr.array())
        if (data.isNotEmpty()) {
            output.write(data)
        }
        output.flush()
    }

    private fun readPacket(input: DataInputStream): Packet {
        val hdrBytes = ByteArray(24)
        input.readFully(hdrBytes)
        val hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = hdr.int
        val arg0 = hdr.int
        val arg1 = hdr.int
        val len = hdr.int
        hdr.int // checksum
        hdr.int // magic
        val data = if (len > 0) ByteArray(len).also { input.readFully(it) } else ByteArray(0)
        return Packet(cmd, arg0, arg1, data)
    }

    private fun readLeInt(b: ByteArray): Int =
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int

    private inner class SyncStream(
        private val input: DataInputStream,
        private val output: OutputStream,
        private val localId: Int,
        private val remoteId: Int,
        private val maxPayload: Int
    ) {
        private val rxBuf = ByteArrayOutputStream()
        private var rxArray = ByteArray(0)
        private var rxOffset = 0

        fun write(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val n = minOf(maxPayload, data.size - off)
                val slice = data.copyOfRange(off, off + n)
                sendPacket(output, A_WRTE, localId, remoteId, slice)
                while (true) {
                    val pkt = readPacket(input)
                    when (pkt.command) {
                        A_OKAY -> break
                        A_WRTE -> {
                            rxBuf.write(pkt.data)
                            sendPacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                        }
                        A_CLSE -> throw IOException("ADB sync closed during write")
                    }
                }
                off += n
            }
        }

        fun readExact(count: Int): ByteArray {
            val out = ByteArray(count)
            var done = 0
            while (done < count) {
                if (rxOffset >= rxArray.size) {
                    if (rxBuf.size() > 0) {
                        rxArray = rxBuf.toByteArray()
                        rxBuf.reset()
                        rxOffset = 0
                    } else {
                        val pkt = readPacket(input)
                        when (pkt.command) {
                            A_WRTE -> {
                                rxArray = pkt.data
                                rxOffset = 0
                                sendPacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                            }
                            A_CLSE -> throw IOException("ADB sync EOF")
                            else -> continue
                        }
                    }
                }
                val take = minOf(count - done, rxArray.size - rxOffset)
                System.arraycopy(rxArray, rxOffset, out, done, take)
                rxOffset += take
                done += take
            }
            return out
        }

        fun close() {
            try {
                sendPacket(output, A_CLSE, localId, remoteId, ByteArray(0))
            } catch (_: Exception) {
            }
        }
    }
}
