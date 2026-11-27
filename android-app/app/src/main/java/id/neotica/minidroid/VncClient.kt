package id.neotica.minidroid

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Pure-Kotlin RFB 3.8 (VNC) client tailored for local QEMU (-vnc 127.0.0.1:1).
 * Streams 32-bit ARGB_8888 pixels directly into a pixel buffer and sends
 * absolute touch pointer events + X11 keysyms back to Goldfish.
 */
class VncClient(
    private val host: String = "127.0.0.1",
    private val port: Int = EmulatorService.VNC_PORT,
    private val onConnected: (width: Int, height: Int) -> Unit,
    private val onFrame: (pixels: IntArray, width: Int, height: Int) -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val writeLock = Any()

    @Volatile
    var fbWidth: Int = 320
        private set

    @Volatile
    var fbHeight: Int = 480
        private set

    fun start() {
        if (running) return
        running = true
        thread(name = "MiniDroidVnc", isDaemon = true) {
            runLoop()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    fun sendPointer(x: Int, y: Int, pressed: Boolean) {
        val cx = x.coerceIn(0, (fbWidth - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (fbHeight - 1).coerceAtLeast(0))
        val mask = if (pressed) 1 else 0
        synchronized(writeLock) {
            val o = out ?: return
            try {
                o.writeByte(5) // PointerEvent
                o.writeByte(mask)
                o.writeShort(cx)
                o.writeShort(cy)
                o.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        synchronized(writeLock) {
            val o = out ?: return
            try {
                o.writeByte(4) // KeyEvent
                o.writeByte(if (down) 1 else 0)
                o.writeShort(0) // padding
                o.writeInt(keysym)
                o.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun tapKey(keysym: Int) {
        thread(isDaemon = true) {
            sendKey(keysym, true)
            try {
                Thread.sleep(45)
            } catch (_: InterruptedException) {
            }
            sendKey(keysym, false)
        }
    }

    private fun runLoop() {
        while (running) {
            try {
                connectAndStream()
            } catch (e: Exception) {
                if (running) {
                    onDisconnected(e.message ?: "Reconnecting...")
                    try {
                        Thread.sleep(600)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun connectAndStream() {
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), 2000)
        sock.tcpNoDelay = true
        sock.soTimeout = 10_000
        socket = sock

        val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 256 * 1024))
        val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 16 * 1024))
        synchronized(writeLock) { out = output }

        // 1. ProtocolVersion handshake
        val ver = ByteArray(12)
        input.readFully(ver)
        output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        output.flush()

        // 2. Security handshake (SecurityType 1 = None)
        val numSec = input.readUnsignedByte()
        if (numSec == 0) {
            val reasonLen = input.readInt()
            val reason = ByteArray(reasonLen).also { input.readFully(it) }
            throw IOException("VNC rejected: ${String(reason)}")
        }
        val secTypes = ByteArray(numSec).also { input.readFully(it) }
        if (!secTypes.contains(1.toByte())) {
            throw IOException("VNC server did not offer None auth")
        }
        output.writeByte(1)
        output.flush()
        val secResult = input.readInt()
        if (secResult != 0) {
            throw IOException("VNC auth failed: $secResult")
        }

        // 3. ClientInit (shared = 1)
        output.writeByte(1)
        output.flush()

        // 4. ServerInit
        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        val pfSkip = ByteArray(16)
        input.readFully(pfSkip)
        val nameLen = input.readInt()
        if (nameLen > 0) {
            val nameBuf = ByteArray(nameLen)
            input.readFully(nameBuf)
        }

        fbWidth = w
        fbHeight = h
        val pixels = IntArray(w * h)
        var rawBuf = ByteArray(w * h * 4)

        // 5. SetPixelFormat: 32bpp, little-endian xRGB8888 (R<<16 | G<<8 | B)
        synchronized(writeLock) {
            output.writeByte(0) // SetPixelFormat
            output.writeByte(0)
            output.writeShort(0)
            output.writeByte(32)  // bits-per-pixel
            output.writeByte(24)  // depth
            output.writeByte(0)   // big-endian = false
            output.writeByte(1)   // true-colour = true
            output.writeShort(255) // red-max
            output.writeShort(255) // green-max
            output.writeShort(255) // blue-max
            output.writeByte(16)   // red-shift
            output.writeByte(8)    // green-shift
            output.writeByte(0)    // blue-shift
            output.writeByte(0)
            output.writeShort(0)

            // 6. SetEncodings: Raw (0), CopyRect (1)
            output.writeByte(2)
            output.writeByte(0)
            output.writeShort(2)
            output.writeInt(0)
            output.writeInt(1)
            output.flush()
        }

        onConnected(w, h)
        sock.soTimeout = 0 // Do not time out while screen is idle between frames
        requestUpdate(incremental = false, w = w, h = h)

        while (running) {
            val msgType = input.readUnsignedByte()
            when (msgType) {
                0 -> { // FramebufferUpdate
                    input.readUnsignedByte() // padding
                    val numRects = input.readUnsignedShort()
                    for (i in 0 until numRects) {
                        val rx = input.readUnsignedShort()
                        val ry = input.readUnsignedShort()
                        val rw = input.readUnsignedShort()
                        val rh = input.readUnsignedShort()
                        val enc = input.readInt()
                        when (enc) {
                            0 -> { // Raw
                                val bytesNeeded = rw * rh * 4
                                if (rawBuf.size < bytesNeeded) {
                                    rawBuf = ByteArray(bytesNeeded)
                                }
                                input.readFully(rawBuf, 0, bytesNeeded)
                                val bb = ByteBuffer.wrap(rawBuf, 0, bytesNeeded)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                for (row in 0 until rh) {
                                    val dstRow = (ry + row) * w + rx
                                    for (col in 0 until rw) {
                                        val px = bb.int
                                        if (dstRow + col in pixels.indices) {
                                            pixels[dstRow + col] = px or -0x1000000
                                        }
                                    }
                                }
                            }
                            1 -> { // CopyRect
                                val sx = input.readUnsignedShort()
                                val sy = input.readUnsignedShort()
                                val tmp = IntArray(rw * rh)
                                for (row in 0 until rh) {
                                    System.arraycopy(pixels, (sy + row) * w + sx, tmp, row * rw, rw)
                                }
                                for (row in 0 until rh) {
                                    System.arraycopy(tmp, row * rw, pixels, (ry + row) * w + rx, rw)
                                }
                            }
                            else -> throw IOException("Unsupported VNC encoding: $enc")
                        }
                    }
                    onFrame(pixels, w, h)
                    requestUpdate(incremental = true, w = w, h = h)
                }
                1 -> { // SetColourMapEntries
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                    val count = input.readUnsignedShort()
                    input.skipBytes(count * 6)
                }
                2 -> { // Bell
                }
                3 -> { // ServerCutText
                    input.skipBytes(3)
                    val len = input.readInt()
                    if (len > 0) {
                        val skip = ByteArray(len)
                        input.readFully(skip)
                    }
                }
                else -> throw IOException("Unknown RFB message type: $msgType")
            }
        }
    }

    private fun requestUpdate(incremental: Boolean, w: Int, h: Int) {
        synchronized(writeLock) {
            val o = out ?: return
            o.writeByte(3) // FramebufferUpdateRequest
            o.writeByte(if (incremental) 1 else 0)
            o.writeShort(0)
            o.writeShort(0)
            o.writeShort(w)
            o.writeShort(h)
            o.flush()
        }
    }
}
