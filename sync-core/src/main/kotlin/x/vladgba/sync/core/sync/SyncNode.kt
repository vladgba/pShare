package x.vladgba.sync.core.sync

import x.vladgba.sync.core.crypto.DeviceIdentity
import x.vladgba.sync.core.model.PeerInfo
import x.vladgba.sync.core.net.Discovery
import x.vladgba.sync.core.net.FolderOffer
import x.vladgba.sync.core.net.Hello
import x.vladgba.sync.core.net.PairDecision
import x.vladgba.sync.core.net.SecureChannel
import x.vladgba.sync.core.net.receiveMsg
import x.vladgba.sync.core.net.sendMsg
import x.vladgba.sync.core.util.Observable
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.random.Random

data class PairingRequest(
    val deviceId: String,
    val name: String,
    val platform: String,
    /** 6-digit code that must be identical on both screens. */
    val code: String,
    val initiatedByUs: Boolean,
)

/** Asks the user to confirm pairing. Called on a background thread; may block until answered. */
fun interface PairingHandler {
    fun confirm(request: PairingRequest): Boolean
}

data class NodeState(
    val running: Boolean = false,
    val port: Int = 0,
    val syncing: Set<String> = emptySet(),
    val lastScan: Long = 0,
)

enum class SyncOutcome { OK, BUSY, FAILED, UNREACHABLE }

/**
 * A LocalBridge device: listens for peers, discovers them on the LAN, scans folders and
 * runs sync sessions. Platform code supplies the folder file systems and a pairing UI.
 * All public methods that do I/O block; call them off the UI thread (or use [launch]).
 */
class SyncNode(
    identity: DeviceIdentity,
    val platform: String,
    val peers: PeerStore,
    val folders: FolderRegistry,
    private val pairing: PairingHandler,
    val log: EventLog,
    private val servicePort: Int = DEFAULT_SERVICE_PORT,
    discoveryPort: Int = Discovery.DEFAULT_PORT,
    private val scanIntervalMs: Long = 60_000,
    private val fullSyncIntervalMs: Long = 15 * 60_000,
    /** Periodic scanning and auto-sync on discovery. Off in tests. */
    private val autoSync: Boolean = true,
    private val enableDiscovery: Boolean = true,
) {
    @Volatile
    var identity: DeviceIdentity = identity

    val deviceId: String get() = identity.deviceId

    val discovery = Discovery(identity.deviceId, { this.identity.name }, platform, discoveryPort)

    val state = Observable(NodeState())

    /** Folders each peer offered in its last session, for "peer shares X — add it?" UI. */
    val remoteOffers = Observable<Map<String, List<FolderOffer>>>(emptyMap())

    private val threadNo = AtomicInteger()
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "lb-worker-${threadNo.incrementAndGet()}").apply { isDaemon = true }
    }
    private var timer: ScheduledExecutorService? = null
    private var server: ServerSocket? = null
    private var discoveryWatch: Closeable? = null
    private val peerLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val scanLock = ReentrantLock()

    /** Runs [block] on a background worker thread. */
    fun launch(block: () -> Unit) {
        workers.execute {
            try {
                block()
            } catch (e: Exception) {
                log.error("Background task failed: ${e.message}")
            }
        }
    }

    @Synchronized
    fun start() {
        if (state.value.running) return
        val ss = ServerSocket().apply { reuseAddress = true }
        try {
            ss.bind(InetSocketAddress(servicePort))
        } catch (e: IOException) {
            ss.bind(InetSocketAddress(0))
        }
        server = ss
        thread(name = "lb-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                launch { handle(s, initiator = false, allowPairing = true, expectedId = null) }
            }
        }
        if (enableDiscovery) {
            try {
                discovery.start(ss.localPort)
            } catch (e: Exception) {
                log.warn("LAN discovery unavailable (${e.message}); connect by address instead")
            }
        }
        if (autoSync) {
            discoveryWatch = watchDiscovery()
            timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lb-periodic").apply { isDaemon = true } }.also {
                var lastFull = 0L
                it.scheduleWithFixedDelay({
                    try {
                        val changed = scanAll()
                        val now = System.currentTimeMillis()
                        if (changed > 0 || now - lastFull > fullSyncIntervalMs) {
                            lastFull = now
                            syncAll()
                        }
                    } catch (e: Exception) {
                        log.error("Periodic sync failed: ${e.message}")
                    }
                }, 2_000, scanIntervalMs, TimeUnit.MILLISECONDS)
            }
        }
        state.update { it.copy(running = true, port = ss.localPort) }
        log.info("Listening on port ${ss.localPort}")
    }

    @Synchronized
    fun stop() {
        timer?.shutdownNow()
        timer = null
        discoveryWatch?.close()
        discoveryWatch = null
        discovery.stop()
        server?.close()
        server = null
        state.set(NodeState())
    }

    /** Scans all folders; returns the number of changed files. */
    fun scanAll(): Int {
        var changed = 0
        scanLock.lock()
        try {
            for (f in folders.all()) {
                if (f.config.paused) continue
                try {
                    changed += f.scan().changed
                } catch (e: Exception) {
                    log.error("${f.config.label}: scan failed — ${e.message}")
                }
            }
        } finally {
            scanLock.unlock()
        }
        state.update { it.copy(lastScan = System.currentTimeMillis()) }
        return changed
    }

    /** Syncs with every paired peer that is visible or was reachable before. */
    fun syncAll() {
        val online = discovery.peers.value
        for (p in peers.items.value) {
            if (p.deviceId in online || p.lastHost != null) syncPeer(p.deviceId)
        }
    }

    /** Scan now and push changes to everyone online. */
    fun syncNow() {
        scanAll()
        syncAll()
    }

    fun syncPeer(deviceId: String): SyncOutcome {
        val d = discovery.peers.value[deviceId]
        val p = peers.get(deviceId) ?: return SyncOutcome.FAILED
        val host = d?.host ?: p.lastHost ?: return SyncOutcome.UNREACHABLE
        val port = d?.port ?: p.lastPort ?: DEFAULT_SERVICE_PORT
        var outcome = connect(host, port, allowPairing = false, expectedId = deviceId, quiet = d == null)
        if (outcome == SyncOutcome.BUSY) {
            Thread.sleep(Random.nextLong(1_000, 4_000))
            outcome = connect(host, port, allowPairing = false, expectedId = deviceId, quiet = d == null)
        }
        return outcome
    }

    /** Connects to an address; with [allowPairing] an unknown device triggers the pairing prompt. */
    fun connect(
        host: String,
        port: Int,
        allowPairing: Boolean,
        expectedId: String? = null,
        quiet: Boolean = false,
    ): SyncOutcome {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 5_000)
        } catch (e: IOException) {
            s.close()
            if (!quiet) log.warn("Can't reach $host:$port — ${e.message}")
            return SyncOutcome.UNREACHABLE
        }
        return handle(s, initiator = true, allowPairing = allowPairing, expectedId = expectedId)
    }

    private fun watchDiscovery(): Closeable {
        var known = emptySet<String>()
        return discovery.peers.observe { map ->
            val trustedOnline = map.keys.filter { peers.get(it) != null }.toSet()
            val appeared: Set<String>
            synchronized(this) {
                appeared = trustedOnline - known
                known = trustedOnline
            }
            for (id in appeared) {
                val d = map[id] ?: continue
                peers.modify(id) { it.copy(lastHost = d.host, lastPort = d.port, lastSeen = System.currentTimeMillis()) }
                // Only one side initiates on discovery, otherwise both connect at once and both get "busy".
                if (deviceId < id) launch { syncPeer(id) }
            }
        }
    }

    private fun handle(socket: Socket, initiator: Boolean, allowPairing: Boolean, expectedId: String?): SyncOutcome {
        var peerName = socket.inetAddress?.hostAddress ?: "peer"
        try {
            socket.use {
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000
                val ch = SecureChannel.handshake(socket, identity, initiator, platform)
                val peerId = ch.peerDeviceId
                peerName = ch.peerHello.name
                if (peerId == deviceId) return SyncOutcome.FAILED
                if (expectedId != null && peerId != expectedId) {
                    log.warn("Address now belongs to another device ($peerName)")
                    return SyncOutcome.FAILED
                }
                if (peers.get(peerId) == null) {
                    if (!allowPairing) return SyncOutcome.FAILED
                    if (!pair(ch, socket, initiator)) return SyncOutcome.FAILED
                }
                ch.setReadTimeout(90_000)

                val lock = peerLocks.computeIfAbsent(peerId) { ReentrantLock() }
                if (!lock.tryLock()) {
                    ch.sendMsg(Hello(identity.name, platform, emptyList(), busy = true))
                    return SyncOutcome.BUSY
                }
                try {
                    state.update { it.copy(syncing = it.syncing + peerId) }
                    return runSession(ch, socket, peerId, initiator)
                } finally {
                    state.update { it.copy(syncing = it.syncing - peerId) }
                    lock.unlock()
                }
            }
        } catch (e: Exception) {
            log.warn("Sync with $peerName failed: ${e.message ?: e.javaClass.simpleName}")
            return SyncOutcome.FAILED
        }
    }

    private fun pair(ch: SecureChannel, socket: Socket, initiator: Boolean): Boolean {
        val name = ch.peerHello.name
        ch.setReadTimeout((PAIRING_TIMEOUT_MS + 30_000).toInt())
        val request = PairingRequest(ch.peerDeviceId, name, ch.peerHello.platform, ch.sas, initiator)
        val future = workers.submit<Boolean> { pairing.confirm(request) }
        val accepted = try {
            future.get(PAIRING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            false
        } catch (e: Exception) {
            false
        }
        ch.sendMsg(PairDecision(accepted))
        val theirs = ch.receiveMsg() as? PairDecision
        if (!accepted || theirs?.accept != true) {
            log.info("Pairing with $name was not confirmed")
            return false
        }
        peers.upsert(
            PeerInfo(
                deviceId = ch.peerDeviceId,
                name = name,
                publicKey = ch.peerPublicKey,
                pairedAt = System.currentTimeMillis(),
                lastHost = ch.remoteHost,
                lastPort = if (initiator) socket.port else null,
            )
        )
        log.info("Paired with $name")
        return true
    }

    private fun runSession(ch: SecureChannel, socket: Socket, peerId: String, initiator: Boolean): SyncOutcome {
        val offered = folders.all().filter { f ->
            !f.config.paused && (f.config.peers.isEmpty() || peerId in f.config.peers)
        }.filter { f ->
            try {
                f.scan(); true
            } catch (e: Exception) {
                log.error("${f.config.label}: not offered — ${e.message}")
                false
            }
        }
        val offers = offered.map { FolderOffer(it.id, it.config.label, it.config.direction.sends, it.config.direction.accepts) }
        ch.sendMsg(Hello(identity.name, platform, offers))
        val hello = ch.receiveMsg() as? Hello ?: throw IOException("Protocol error: expected hello")
        if (hello.busy) return SyncOutcome.BUSY
        remoteOffers.update { it + (peerId to hello.folders) }

        val summary = SyncSession(ch, offered.associateBy { it.id }, hello.folders.associateBy { it.folderId }, log).run()
        val now = System.currentTimeMillis()
        peers.modify(peerId) {
            it.copy(
                name = hello.name,
                lastHost = ch.remoteHost ?: it.lastHost,
                lastPort = if (initiator) socket.port else it.lastPort,
                lastSeen = now,
                lastSync = now,
            )
        }
        if (!summary.isEmpty) log.info("Synced with ${hello.name}: $summary")
        return SyncOutcome.OK
    }

    companion object {
        const val DEFAULT_SERVICE_PORT = 47431
        const val PAIRING_TIMEOUT_MS = 90_000L
    }
}
