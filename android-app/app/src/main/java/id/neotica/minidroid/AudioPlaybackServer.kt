package id.neotica.minidroid

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Streams 44.1 kHz 16-bit LE stereo PCM from Goldfish audio (/dev/eac via QEMU chardev "audio0"
 * connected to 127.0.0.1:15558) straight into Android's native AudioTrack.
 */
class AudioPlaybackServer(
    private val port: Int = EmulatorService.AUDIO_PORT
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null

    fun start() {
        if (running) return
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
        }
        serverSocket = ss
        running = true

        thread(name = "MiniDroidAudio", isDaemon = true) {
            try {
                while (running) {
                    val sock = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    clientSocket = sock
                    streamAudio(sock)
                }
            } finally {
                try {
                    ss.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        running = false
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

    private fun streamAudio(sock: Socket) {
        val sampleRate = 44100
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        val bufSize = maxOf(minBuf * 2, 16384)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            try {
                sock.close()
            } catch (_: Exception) {
            }
            return
        }

        var playing = false
        val rx = ByteArray(4096)

        try {
            sock.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 1500
                val input = s.getInputStream()
                while (running) {
                    val n = try {
                        input.read(rx)
                    } catch (_: SocketTimeoutException) {
                        if (playing) {
                            try {
                                track.pause()
                                track.flush()
                            } catch (_: Exception) {
                            }
                            playing = false
                        }
                        continue
                    }
                    if (n <= 0) break

                    if (!playing) {
                        track.play()
                        playing = true
                    }
                    track.write(rx, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                if (playing) track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
