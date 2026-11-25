package x.vladgba.sync.core

import x.vladgba.sync.core.crypto.DeviceIdentity
import x.vladgba.sync.core.fs.LocalFileSystem
import x.vladgba.sync.core.model.FolderConfig
import x.vladgba.sync.core.model.SyncDirection
import x.vladgba.sync.core.sync.EventLog
import x.vladgba.sync.core.sync.FolderConfigStore
import x.vladgba.sync.core.sync.FolderRegistry
import x.vladgba.sync.core.sync.PairingHandler
import x.vladgba.sync.core.sync.PeerStore
import x.vladgba.sync.core.sync.SyncNode
import x.vladgba.sync.core.sync.SyncOutcome
import java.io.File
import java.nio.file.Files
import x.vladgba.sync.core.testing.AfterTest
import x.vladgba.sync.core.testing.Test
import x.vladgba.sync.core.testing.assertEquals
import x.vladgba.sync.core.testing.assertFalse
import x.vladgba.sync.core.testing.assertTrue

/** Two real nodes talking over localhost TCP with the encrypted channel. */
class EndToEndTest {
    private val nodes = mutableListOf<SyncNode>()
    private var clock = System.currentTimeMillis() - 3_600_000

    private inner class Device(name: String, direction: SyncDirection = SyncDirection.TWO_WAY, var acceptPairing: Boolean = true) {
        val home: File = Files.createTempDirectory("lb-$name").toFile()
        val folder = File(home, "Sync").apply { mkdirs() }
        val identity = DeviceIdentity.generate(name)
        val log = EventLog(sink = { println("[$name] ${it.level} ${it.text}") })
        val peers = PeerStore(File(home, "peers.json"))
        val configs = FolderConfigStore(File(home, "folders.json"))
        val registry = FolderRegistry(File(home, "data"), identity.deviceId) { LocalFileSystem(File(it.location)) }
        var lastCode: String? = null
        val node = SyncNode(
            identity, "test", peers, registry,
            PairingHandler { req -> lastCode = req.code; acceptPairing },
            log, servicePort = 0, autoSync = false, enableDiscovery = false,
        ).also { nodes += it }

        init {
            configs.upsert(FolderConfig("docs", "Docs", folder.absolutePath, direction))
            registry.apply(configs.items.value)
            node.start()
        }

        val port get() = node.state.value.port

        /** Writes with an mtime in the past so the scanner doesn't treat it as still being written. */
        fun write(path: String, text: String) = write(path, text.toByteArray())
        fun write(path: String, bytes: ByteArray) {
            val f = File(folder, path)
            f.parentFile.mkdirs()
            f.writeBytes(bytes)
            clock += 5_000
            f.setLastModified(clock)
        }

        fun read(path: String) = File(folder, path).readText()
        fun exists(path: String) = File(folder, path).exists()
        fun tree(): Map<String, String> = folder.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(folder).invariantSeparatorsPath to it.readBytes().contentHashCode().toString() }
    }

    @AfterTest
    fun tearDown() = nodes.forEach { it.stop() }

    private fun pairAndSync(a: Device, b: Device) =
        assertEquals(SyncOutcome.OK, a.node.connect("127.0.0.1", b.port, allowPairing = true))

    private fun sync(a: Device, b: Device) =
        assertEquals(SyncOutcome.OK, a.node.connect("127.0.0.1", b.port, allowPairing = false))

    @Test
    fun fullScenario() {
        val a = Device("Phone")
        val b = Device("Laptop")
        a.write("notes/todo.md", "- milk\n- bread\n- eggs\n- tea\n")
        a.write("photo.jpg", byteArrayOf(1, 2, 3, 0, 5))
        b.write("laptop.txt", "from laptop\n")

        pairAndSync(a, b)
        assertEquals(a.lastCode, b.lastCode, "both screens must show the same pairing code")
        assertTrue(a.peers.get(b.identity.deviceId) != null && b.peers.get(a.identity.deviceId) != null)
        assertEquals(a.tree(), b.tree())
        assertEquals(3, a.tree().size)

        // Non-overlapping concurrent edits of a text file → clean 3-way merge on both sides.
        a.write("notes/todo.md", "- MILK\n- bread\n- eggs\n- tea\n")
        b.write("notes/todo.md", "- milk\n- bread\n- eggs\n- green tea\n")
        sync(b, a)
        val merged = "- MILK\n- bread\n- eggs\n- green tea\n"
        assertEquals(merged, a.read("notes/todo.md"))
        assertEquals(merged, b.read("notes/todo.md"))

        // Concurrent edits of a binary file → both versions kept, same result on both devices.
        a.write("photo.jpg", byteArrayOf(9, 9, 0, 9))
        b.write("photo.jpg", byteArrayOf(7, 0, 7))
        sync(a, b)
        assertEquals(a.tree(), b.tree())
        assertEquals(1, a.tree().keys.count { it.startsWith("photo.conflict-") })

        // Stable afterwards: another round changes nothing.
        val before = a.tree()
        sync(b, a)
        assertEquals(before, a.tree())
        assertEquals(before, b.tree())

        // Deletion propagates and the old content is kept in the version archive.
        File(a.folder, "laptop.txt").delete()
        sync(a, b)
        assertFalse(b.exists("laptop.txt"))
        assertEquals(1, b.registry.get("docs")!!.archive.versionsOf("laptop.txt").size)

        // Delete vs edit → the edit wins.
        File(a.folder, "notes/todo.md").delete()
        b.write("notes/todo.md", "edited on laptop\n")
        sync(a, b)
        assertEquals("edited on laptop\n", a.read("notes/todo.md"))
        assertEquals(a.tree(), b.tree())
    }

    @Test
    fun moveOutDeletesAfterPeerStoredIt() {
        val phone = Device("Phone", SyncDirection.MOVE_OUT)
        val pc = Device("PC", SyncDirection.RECEIVE_ONLY)
        phone.write("DCIM/img1.jpg", byteArrayOf(1, 0, 1))
        phone.write("DCIM/img2.jpg", byteArrayOf(2, 0, 2))
        pairAndSync(phone, pc)
        assertTrue(pc.exists("DCIM/img1.jpg") && pc.exists("DCIM/img2.jpg"))
        assertFalse(phone.exists("DCIM/img1.jpg") || phone.exists("DCIM/img2.jpg"))
        // And the PC keeps them on the next sync (no deletion propagated back).
        sync(phone, pc)
        assertTrue(pc.exists("DCIM/img1.jpg"))
    }

    @Test
    fun rejectedPairingSyncsNothing() {
        val a = Device("A")
        val b = Device("B", acceptPairing = false)
        a.write("x.txt", "x\n")
        assertEquals(SyncOutcome.FAILED, a.node.connect("127.0.0.1", b.port, allowPairing = true))
        assertFalse(b.exists("x.txt"))
        assertTrue(a.peers.items.value.isEmpty())
        // Unpaired peers can't sync without pairing.
        assertEquals(SyncOutcome.FAILED, a.node.connect("127.0.0.1", b.port, allowPairing = false))
    }
}
