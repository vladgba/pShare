package x.vladgba.share.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 4-symbol pairing code generation and local network discovery.
 *
 * Allows two devices on the same Wi-Fi / LAN to pair by typing a 4-symbol code
 * instead of scanning a QR code with a camera.
 */
object PairingCode {
    const val DISCOVERY_PORT = 52385
    const val TYPE_QUERY = 0x10
    const val TYPE_ANNOUNCE = 0x11

    // Omits confusing characters 0/O and 1/I to make manual entry easy.
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val random = SecureRandom()

    fun generate(): String {
        val sb = StringBuilder(4)
        repeat(4) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }

    fun normalize(code: String): String =
        code.trim().uppercase()
            .replace('O', '0')
            .replace('I', '1')

    fun isMatch(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.length != 4 || nb.length != 4) return false
        // Allow fuzzy match for normalized 0/O and 1/I
        for (i in 0 until 4) {
            val ca = na[i]
            val cb = nb[i]
            if (ca != cb) {
                val match0 = (ca == '0' || ca == 'O') && (cb == '0' || cb == 'O')
                val match1 = (ca == '1' || ca == 'I' || ca == 'L') && (cb == '1' || cb == 'I' || cb == 'L')
                if (!match0 && !match1) return false
            }
        }
        return true
    }

    fun encodeQuery(code: String): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeByte(Wire.MAGIC)
            out.writeByte(TYPE_QUERY)
            out.write(code.padEnd(4, ' ').take(4).toByteArray(Charsets.US_ASCII))
        }
        return bos.toByteArray()
    }

    fun encodeAnnounce(code: String, pairingBytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeByte(Wire.MAGIC)
            out.writeByte(TYPE_ANNOUNCE)
            out.write(code.padEnd(4, ' ').take(4).toByteArray(Charsets.US_ASCII))
            out.writeShort(pairingBytes.size)
            out.write(pairingBytes)
        }
        return bos.toByteArray()
    }

    fun decodeQuery(bytes: ByteArray): String? {
        if (bytes.size < 6) return null
        if ((bytes[0].toInt() and 0xFF) != Wire.MAGIC || (bytes[1].toInt() and 0xFF) != TYPE_QUERY) return null
        return String(bytes, 2, 4, Charsets.US_ASCII).trim()
    }

    fun decodeAnnounce(bytes: ByteArray): Pair<String, ByteArray>? {
        if (bytes.size < 8) return null
        if ((bytes[0].toInt() and 0xFF) != Wire.MAGIC || (bytes[1].toInt() and 0xFF) != TYPE_ANNOUNCE) return null
        return try {
            val code = String(bytes, 2, 4, Charsets.US_ASCII).trim()
            val dis = DataInputStream(ByteArrayInputStream(bytes, 6, bytes.size - 6))
            val len = dis.readUnsignedShort()
            if (len > bytes.size - 8) return null
            val payload = ByteArray(len)
            dis.readFully(payload)
            code to payload
        } catch (_: IOException) {
            null
        }
    }

    fun broadcastTargets(): List<InetAddress> = try {
        val list = ArrayList<InetAddress>()
        list.add(InetAddress.getByName("255.255.255.255"))
        list.add(InetAddress.getByName("127.0.0.1"))
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .forEach(list::add)
        list.distinct()
    } catch (_: Exception) {
        listOf(InetAddress.getByName("255.255.255.255"), InetAddress.getByName("127.0.0.1"))
    }
}

/**
 * Runs on the host (displaying the 4-symbol code):
 * - Periodically broadcasts ANNOUNCE packets with pairing payload on LAN.
 * - Listens for QUERY packets matching its 4-symbol code and responds directly.
 */
class CodeAnnouncer(
    val code: String,
    val pairingBytes: ByteArray,
    val port: Int = PairingCode.DISCOVERY_PORT,
) {
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null

    fun start(): CodeAnnouncer {
        val s = try {
            DatagramSocket(port).apply { broadcast = true }
        } catch (e: Exception) {
            try {
                DatagramSocket().apply { broadcast = true }
            } catch (e2: Exception) {
                return this
            }
        }
        socket = s

        // Thread 1: listen for incoming queries
        thread(name = "code-announce-rx", isDaemon = true) {
            val buf = ByteArray(2048)
            while (!stopped.get() && !s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val incoming = buf.copyOf(p.length)
                    val queryCode = PairingCode.decodeQuery(incoming)
                    if (queryCode != null && PairingCode.isMatch(queryCode, code)) {
                        val reply = PairingCode.encodeAnnounce(code, pairingBytes)
                        s.send(DatagramPacket(reply, reply.size, p.socketAddress))
                    }
                } catch (_: Exception) {
                    if (stopped.get()) break
                }
            }
        }

        // Thread 2: periodic broadcast beacon
        thread(name = "code-announce-tx", isDaemon = true) {
            val beacon = PairingCode.encodeAnnounce(code, pairingBytes)
            while (!stopped.get() && !s.isClosed) {
                try {
                    for (target in PairingCode.broadcastTargets()) {
                        try {
                            s.send(DatagramPacket(beacon, beacon.size, target, port))
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        return this
    }

    fun stop() {
        stopped.set(true)
        try { socket?.close() } catch (_: Exception) {}
    }
}

/**
 * Runs on the client (the user entered a 4-symbol code):
 * - Sends QUERY broadcast packets.
 * - Probes the local subnet (/24) unicast directly in case router drops broadcast.
 * - Listens for ANNOUNCE packets matching the code.
 * - When found, delivers the decoded [Pairing] and finishes.
 */
class CodeFinder(
    val code: String,
    val port: Int = PairingCode.DISCOVERY_PORT,
    val onPairingFound: (Pairing) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null

    fun start(): CodeFinder {
        val s = try {
            DatagramSocket().apply { broadcast = true }
        } catch (_: Exception) {
            return this
        }
        socket = s

        // Receiver thread
        thread(name = "code-finder-rx", isDaemon = true) {
            val buf = ByteArray(2048)
            while (!stopped.get() && !s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val incoming = buf.copyOf(p.length)
                    val pair = PairingCode.decodeAnnounce(incoming)
                    if (pair != null && PairingCode.isMatch(pair.first, code)) {
                        val pairing = Pairing.decode(pair.second)
                        if (pairing != null && !stopped.getAndSet(true)) {
                            onPairingFound(pairing)
                            s.close()
                            break
                        }
                    }
                } catch (_: Exception) {
                    if (stopped.get()) break
                }
            }
        }

        // Sender thread
        thread(name = "code-finder-tx", isDaemon = true) {
            val query = PairingCode.encodeQuery(code)
            var attempt = 0
            while (!stopped.get() && !s.isClosed) {
                attempt++
                // 1. Broadcast targets
                for (target in PairingCode.broadcastTargets()) {
                    try {
                        s.send(DatagramPacket(query, query.size, target, port))
                    } catch (_: Exception) {}
                }

                // 2. Local subnet probe (every 2nd attempt, to bypass router broadcast blocking)
                if (attempt % 2 == 1) {
                    probeLocalSubnets(s, query)
                }

                try {
                    Thread.sleep(1200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        return this
    }

    private fun probeLocalSubnets(s: DatagramSocket, query: ByteArray) {
        try {
            val ips = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }

            for (ip in ips) {
                val bytes = ip.address
                val prefix = "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}."
                val myLastByte = bytes[3].toInt() and 0xFF
                for (i in 1..254) {
                    if (i == myLastByte) continue
                    if (stopped.get()) return
                    try {
                        val addr = InetAddress.getByName("$prefix$i")
                        s.send(DatagramPacket(query, query.size, addr, port))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        stopped.set(true)
        try { socket?.close() } catch (_: Exception) {}
    }
}
