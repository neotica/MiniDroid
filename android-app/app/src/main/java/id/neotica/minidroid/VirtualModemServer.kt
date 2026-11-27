package id.neotica.minidroid

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Calendar
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Lightweight virtual GSM/3G AT modem for Android 1.6's rild (/dev/ttyS1 via -serial tcp:127.0.0.1:15556).
 * Reports a ready SIM, full 3G/UMTS registration, active PDP context on eth0 (10.0.2.15),
 * and current host time/timezone (%CTZV) so Android 1.6 marks Mobile Data as CONNECTED
 * and routes all app/browser traffic through QEMU SLiRP over the host phone's Wi-Fi or Mobile Data.
 */
class VirtualModemServer(
    private val port: Int = EmulatorService.MODEM_PORT
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

        thread(name = "MiniDroidModem", isDaemon = true) {
            try {
                while (running) {
                    val sock = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    clientSocket = sock
                    handleConnection(sock)
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

    private fun handleConnection(sock: Socket) {
        val state = ModemState()
        sock.use { s ->
            s.tcpNoDelay = true
            val input: InputStream = s.getInputStream()
            val output = BufferedOutputStream(s.getOutputStream(), 1024)
            val lineBuf = StringBuilder(256)
            val rx = ByteArray(512)

            try {
                while (running) {
                    val n = input.read(rx)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        val c = rx[i].toInt() and 0xFF
                        if (c == '\r'.code || c == '\n'.code) {
                            if (lineBuf.isNotEmpty()) {
                                val line = lineBuf.toString().trim()
                                lineBuf.setLength(0)
                                if (line.isNotEmpty()) {
                                    val resp = state.handle(line)
                                    if (resp != null) {
                                        output.write((resp + "\r").toByteArray(Charsets.ISO_8859_1))
                                        output.flush()
                                    }
                                }
                            }
                        } else if (lineBuf.length < 512) {
                            lineBuf.append(c.toChar())
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    internal class ModemState {
        private var radioOn = false
        private var voiceMode = 2
        private var dataMode = 1
        private var pdpActive = false
        private var pdpApn = "internet"
        private var operFmt = 2

        fun handle(line: String): String? {
            if (!line.startsWith("AT")) return null
            val cmd = line.substring(2)
            if (cmd.isEmpty()) return "OK"

            return when {
                cmd == "+CFUN=0" -> {
                    radioOn = false
                    "OK"
                }
                cmd == "+CFUN=1" -> {
                    radioOn = true
                    "OK"
                }
                cmd == "+CFUN?" -> "+CFUN: ${if (radioOn) 1 else 0}\rOK"
                cmd == "+CPIN?" -> "+CPIN: READY\rOK"
                cmd == "+CSQ" -> "+CSQ: 24,99\rOK"
                cmd == "+CSMS=1" -> "+CSMS: 1, 1, 1\rOK"
                cmd == "+CNMI?" -> "+CNMI: 1,2,2,1,1\rOK"
                cmd == "+CIMI" -> "310260000000000\rOK"
                cmd == "+CGSN" -> "000000000000000\rOK"
                cmd == "+CLCC" -> "OK"
                cmd == "+COPS=3,0;+COPS?;+COPS=3,1;+COPS?;+COPS=3,2;+COPS?" -> {
                    operFmt = 2
                    "+COPS: 0,0,\"Android\"\r+COPS: 0,1,\"Android\"\r+COPS: 0,2,\"310260\"\rOK"
                }
                cmd == "+COPS?" -> {
                    if (!radioOn) "+CME ERROR: 30"
                    else if (operFmt == 2) "+COPS: 0,2,310260\rOK"
                    else "+COPS: 0,0,\"Android\"\rOK"
                }
                cmd == "+COPS=?" -> "+COPS: (2,\"Android\",\"Android\",\"310260\")\rOK"
                cmd.startsWith("+COPS=3,") -> {
                    operFmt = cmd.substring(8).take(1).toIntOrNull() ?: operFmt
                    "OK"
                }
                cmd.startsWith("+COPS=") -> "OK"
                cmd == "+CREG?" -> "+CREG: $voiceMode,1, \"0000\", \"0000\"\rOK"
                cmd.startsWith("+CREG=") -> {
                    if (cmd == "+CREG=?") "+CREG: (0-2)\rOK"
                    else {
                        voiceMode = cmd.substring(6).take(1).toIntOrNull() ?: voiceMode
                        "OK"
                    }
                }
                cmd == "+CGREG?" -> "+CGREG: $dataMode,1,\"0000\",\"0000\",\"0003\"\rOK"
                cmd.startsWith("+CGREG=") -> {
                    if (cmd == "+CGREG=?") "+CGREG: (0-2)\rOK"
                    else {
                        dataMode = cmd.substring(7).take(1).toIntOrNull() ?: dataMode
                        "OK"
                    }
                }
                cmd == "+CGACT?" -> if (pdpActive) "+CGACT: 1,1\rOK" else "OK"
                cmd == "+CGDCONT?" -> {
                    if (pdpActive) "+CGDCONT: 1,\"IP\",\"$pdpApn\",\"10.0.2.15\",0,0\rOK"
                    else "OK"
                }
                cmd.startsWith("+CGDCONT=") -> {
                    val parts = cmd.substring(9).split(",")
                    if (parts.size >= 3) {
                        pdpApn = parts[2].trim('"').ifEmpty { "internet" }
                    }
                    pdpActive = true
                    "OK"
                }
                cmd == "D*99***1#" -> {
                    pdpActive = true
                    "OK"
                }
                cmd.startsWith("+CRSM=") -> {
                    val ans = SIM_ANSWERS[cmd] ?: "+CRSM: 148,4"
                    "$ans\rOK"
                }
                cmd == "+CMGF=0" -> formatNitzResponse() + "\rOK"
                else -> "OK"
            }
        }

        private fun formatNitzResponse(): String {
            val nowMs = System.currentTimeMillis()
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
            val localTz = TimeZone.getDefault()
            val offsetQuarters = localTz.getOffset(nowMs) / (15 * 60 * 1000)
            val sign = if (offsetQuarters >= 0) "+" else "-"
            val absQuarters = kotlin.math.abs(offsetQuarters)
            val dst = if (localTz.inDaylightTime(java.util.Date(nowMs))) 1 else 0
            val tzName = localTz.id.replace('/', '!')
            return String.format(
                java.util.Locale.US,
                "%%CTZV: %02d/%02d/%02d:%02d:%02d:%02d%s%d:%d:%s",
                utc.get(Calendar.YEAR) % 100,
                utc.get(Calendar.MONTH) + 1,
                utc.get(Calendar.DAY_OF_MONTH),
                utc.get(Calendar.HOUR_OF_DAY),
                utc.get(Calendar.MINUTE),
                utc.get(Calendar.SECOND),
                sign,
                absQuarters,
                dst,
                tzName
            )
        }
    }

    companion object {
        private val SIM_ANSWERS = mapOf(
            "+CRSM=192,28436,0,0,15" to "+CRSM: 144,0,000000146f1404001aa0aa01020000",
            "+CRSM=176,28436,0,0,20" to "+CRSM: 144,0,416e64726f6964ffffffffffffffffffffffffff",
            "+CRSM=192,28433,0,0,15" to "+CRSM: 144,0,000000016f11040011a0aa01020000",
            "+CRSM=176,28433,0,0,1" to "+CRSM: 144,0,55",
            "+CRSM=192,12258,0,0,15" to "+CRSM: 144,0,0000000a2fe204000fa0aa01020000",
            "+CRSM=176,12258,0,0,10" to "+CRSM: 144,0,98101430121181157002",
            "+CRSM=192,28435,0,0,15" to "+CRSM: 144,0,000000016f13040011a0aa01020000",
            "+CRSM=176,28435,0,0,1" to "+CRSM: 144,0,55",
            "+CRSM=192,28472,0,0,15" to "+CRSM: 144,0,0000000f6f3804001aa0aa01020000",
            "+CRSM=176,28472,0,0,15" to "+CRSM: 144,0,ff30ffff3c003c03000c0000f03f00",
            "+CRSM=192,28617,0,0,15" to "+CRSM: 144,0,000000086fc9040011a0aa01020104",
            "+CRSM=178,28617,1,4,4" to "+CRSM: 144,0,01000000",
            "+CRSM=192,28618,0,0,15" to "+CRSM: 144,0,0000000a6fca040011a0aa01020105",
            "+CRSM=178,28618,1,4,5" to "+CRSM: 144,0,0000000000",
            "+CRSM=192,28589,0,0,15" to "+CRSM: 144,0,000000046fad04000aa0aa01020000",
            "+CRSM=176,28589,0,0,4" to "+CRSM: 144,0,00000003",
            "+CRSM=192,28438,0,0,15" to "+CRSM: 144,0,000000026f1604001aa0aa01020000",
            "+CRSM=176,28438,0,0,2" to "+CRSM: 144,0,0233",
            "+CRSM=192,28486,0,0,15" to "+CRSM: 148,4",
            "+CRSM=192,28621,0,0,15" to "+CRSM: 148,4",
            "+CRSM=192,28613,0,0,15" to "+CRSM: 144,0,000000f06fc504000aa0aa01020118",
            "+CRSM=178,28613,1,4,24" to "+CRSM: 144,0,43058441aa890affffffffffffffffffffffffffffffffff",
            "+CRSM=192,28480,0,0,15" to "+CRSM: 144,0,000000806f40040011a0aa01020120",
            "+CRSM=178,28480,1,4,32" to "+CRSM: 144,0,ffffffffffffffffffffffffffffffffffff07815155258131f5ffffffffffff",
            "+CRSM=192,28615,0,0,15" to "+CRSM: 144,0,000000406fc7040011a0aa01020120",
            "+CRSM=178,28615,1,4,32" to "+CRSM: 144,0,566f6963656d61696cffffffffffffffffff07915155125740f9ffffffffffff"
        )
    }
}
