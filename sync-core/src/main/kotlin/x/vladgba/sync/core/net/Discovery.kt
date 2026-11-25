package x.vladgba.sync.core.net

import x.vladgba.sync.core.util.Json
import x.vladgba.sync.core.util.Observable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class DiscoveredPeer(val deviceId: String, val name: String, val host: String, val port: Int, val platform: String, val lastSeen: Long)

/**
 * LAN discovery by UDP broadcast beacons. Plain java.net, so the same code runs on Android
 * and Windows. Beacons are unauthenticated hints only — every connection is authenticated.
 */
class Discovery(
    private val selfId: String,
    private val name: () -> String,
    private val platform: String,
    private val discoveryPort: Int = DEFAULT_PORT,
) {
    val peers = Observable<Map<String, DiscoveredPeer>>(emptyMap())

    private var socket: DatagramSocket? = null
    private var timer: ScheduledExecutorService? = null
    private var servicePort = 0

    @Synchronized
    fun start(servicePort: Int) {
        stop()
        this.servicePort = servicePort
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(discoveryPort))
        }
        socket = s
        thread(name = "lb-discovery-rx", isDaemon = true) { receiveLoop(s) }
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lb-discovery-tx").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                announce(s, null)
                val cutoff = System.currentTimeMillis() - EXPIRE_MS
                peers.update { m -> m.filterValues { p -> p.lastSeen >= cutoff } }
            }, 0, ANNOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        timer?.shutdownNow()
        timer = null
        socket?.close()
        socket = null
        peers.set(emptyMap())
    }

    /** Broadcast now, e.g. after the network changed. */
    fun poke() {
        val s = socket ?: return
        timer?.execute { announce(s, null) }
    }

    private fun beaconBytes() = Json.stringify(
        mapOf("app" to APP, "v" to 1, "id" to selfId, "name" to name(), "port" to servicePort, "platform" to platform)
    ).toByteArray()

    private fun announce(s: DatagramSocket, to: InetAddress?) {
        val bytes = beaconBytes()
        val targets = if (to != null) listOf(to) else broadcastAddresses()
        for (addr in targets) {
            try {
                s.send(DatagramPacket(bytes, bytes.size, addr, discoveryPort))
            } catch (_: Exception) {
            }
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (!s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: SocketException) {
                return
            } catch (e: Exception) {
                continue
            }
            val peer = try {
                val o = Json.parseObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                if (o.str("app") != APP) continue
                DiscoveredPeer(
                    o.str("id"), o.str("name"), packet.address.hostAddress ?: continue,
                    o.int("port"), o.strOr("platform", "?")!!, System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                continue
            }
            if (peer.deviceId == selfId) continue
            val isNew = peer.deviceId !in peers.value
            peers.update { it + (peer.deviceId to peer) }
            if (isNew) announce(s, packet.address) // answer directly so the newcomer sees us immediately
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            }
        } catch (_: Exception) {
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }

    companion object {
        const val APP = "LocalBridge"
        const val DEFAULT_PORT = 47430
        const val ANNOUNCE_MS = 5_000L
        const val EXPIRE_MS = 20_000L
    }
}
