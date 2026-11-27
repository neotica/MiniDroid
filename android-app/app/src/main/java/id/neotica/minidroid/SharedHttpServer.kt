package id.neotica.minidroid

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Lightweight local HTTP file server bound to 127.0.0.1:18080.
 * Inside Android 1.6 (via QEMU SLiRP), this is reachable at http://10.0.2.2:18080/
 * so you can browse and tap APKs straight from Android 1.6's built-in Browser.
 */
class SharedHttpServer(
    private val context: Context,
    private val port: Int = PORT
) {
    companion object {
        const val PORT = 18080
        const val PREFS = "minidroid_prefs"
        const val KEY_SHARED_TREE_URI = "shared_tree_uri"
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "SharedHttpServer", isDaemon = true) {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
                }
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    thread(isDaemon = true) { handleClient(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun getSelectedTree(): DocumentFile? {
        val uriStr = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SHARED_TREE_URI, null) ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
        } catch (_: Exception) {
            null
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val reqLine = reader.readLine() ?: return
            // Drain all HTTP request headers before responding so closing the socket never sends TCP RST
            while (true) {
                val headerLine = reader.readLine()
                if (headerLine.isNullOrEmpty()) break
            }
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val headOnly = method == "HEAD"
            val rawPath = parts[1].substringBefore("?")
            val path = URLDecoder.decode(rawPath, "UTF-8")
            val out = s.getOutputStream()

            if (path == "/" || path.isEmpty()) {
                serveIndex(out, headOnly)
            } else {
                val name = path.trimStart('/')
                serveFile(out, name, headOnly)
            }
            try {
                s.shutdownOutput()
            } catch (_: Exception) {
            }
        }
    }

    private fun serveIndex(out: OutputStream, headOnly: Boolean) {
        val sb = StringBuilder()
        sb.append("<html><head><title>MiniDroid Shared Folder</title>")
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
        sb.append("<style>body{font-family:sans-serif;background:#181a1f;color:#eee;padding:12px;}")
        sb.append("a{display:block;padding:10px;margin:6px 0;background:#2a2e36;color:#9ccc65;text-decoration:none;border-radius:4px;}</style>")
        sb.append("</head><body><h3>MiniDroid Shared Storage</h3>")

        val tree = getSelectedTree()
        var count = 0
        if (tree != null && tree.isDirectory) {
            for (doc in tree.listFiles().sortedBy { it.name?.lowercase() ?: "" }) {
                val name = doc.name ?: continue
                if (doc.isFile) {
                    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    val kb = (doc.length() + 1023) / 1024
                    sb.append("<a href='/$encoded'>$name (${kb} KB)</a>")
                    count++
                }
            }
        } else {
            val dir = ImageInstaller.defaultSharedDir(context)
            for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }) {
                if (f.isFile) {
                    val encoded = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
                    val kb = (f.length() + 1023) / 1024
                    sb.append("<a href='/$encoded'>${f.name} (${kb} KB)</a>")
                    count++
                }
            }
        }

        if (count == 0) {
            sb.append("<p>No files yet. Add .apk or other files via the MiniDroid Shared Storage screen.</p>")
        }
        sb.append("</body></html>")

        val body = sb.toString().toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        if (!headOnly) {
            out.write(body)
        }
        out.flush()
    }

    private fun serveFile(out: OutputStream, name: String, headOnly: Boolean) {
        if (name.contains("..") || name.contains("/")) {
            send404(out, headOnly)
            return
        }
        val mime = if (name.endsWith(".apk", ignoreCase = true)) {
            "application/vnd.android.package-archive"
        } else {
            "application/octet-stream"
        }

        val tree = getSelectedTree()
        if (tree != null && tree.isDirectory) {
            val doc = tree.findFile(name)
            if (doc != null && doc.isFile) {
                val len = doc.length()
                val hdr = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: $len\r\nContent-Disposition: attachment; filename=\"$name\"\r\nConnection: close\r\n\r\n"
                out.write(hdr.toByteArray(Charsets.US_ASCII))
                if (!headOnly) {
                    context.contentResolver.openInputStream(doc.uri)?.use { it.copyTo(out) }
                }
                out.flush()
                return
            }
        }

        val f = File(ImageInstaller.defaultSharedDir(context), name)
        if (f.exists() && f.isFile) {
            val hdr = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${f.length()}\r\nContent-Disposition: attachment; filename=\"$name\"\r\nConnection: close\r\n\r\n"
            out.write(hdr.toByteArray(Charsets.US_ASCII))
            if (!headOnly) {
                FileInputStream(f).use { it.copyTo(out) }
            }
            out.flush()
            return
        }

        send404(out, headOnly)
    }

    private fun send404(out: OutputStream, headOnly: Boolean) {
        val body = "Not found".toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        if (!headOnly) {
            out.write(body)
        }
        out.flush()
    }
}
