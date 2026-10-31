package x.vladgba.share

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import x.vladgba.share.core.*
import x.vladgba.share.data.DownloadSink
import x.vladgba.share.data.TreeWalker
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** No test-library dependency; run with `adb shell am instrument -w ...test/...FolderTransferInstrumentation`. */
class FolderTransferInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val mode = args.getString("mode") ?: "local"
            val root = args.getString("root") ?: "FolderRegression-${System.currentTimeMillis()}"
            when (mode) {
                "send" -> Socket(args.getString("peer"), 45879).use { send(it, root) }
                "receive" -> ServerSocket(45879).use { server ->
                    server.soTimeout = 45_000
                    server.accept().use { receive(it, root) }
                }
                else -> {
                    verifyTreeWalker()
                    verifyQr(root)
                    ServerSocket(0).use { server ->
                        var error: Throwable? = null
                        val sender = thread {
                            try { Socket("127.0.0.1", server.localPort).use { send(it, "$root-stream") } }
                            catch (t: Throwable) { error = t }
                        }
                        server.soTimeout = 20_000
                        server.accept().use { receive(it, "$root-stream") }
                        sender.join(20_000)
                        check(!sender.isAlive) { "Sender did not finish" }
                        error?.let { throw it }
                    }
                }
            }
            result.putString("stream", "PASS: $mode folder transfer, hierarchy, empty directories, contents and zero-byte files\n")
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun items(root: String): List<OutgoingItem> = listOf(
        DirItem(root), DirItem("$root/empty"), DirItem("$root/nested"),
        bytes("$root/photo.txt", "root photo"), bytes("$root/nested/photo.txt", "nested photo"),
        bytes("$root/nested/zero.bin", ""), DirItem("$root/nested/deep"),
    )
    private fun bytes(name: String, text: String) = object : OutgoingItem {
        private val data = text.toByteArray()
        override val info = ItemInfo(ItemInfo.KIND_FILE, name, "application/octet-stream", data.size.toLong())
        override fun open() = data.inputStream()
    }
    // Test-only fixed secrets; never used by the app's pairing flow.
    private val key = ByteArray(32) { it.toByte() }
    private val session = ByteArray(8) { it.toByte() }
    private fun send(socket: Socket, root: String) {
        socket.soTimeout = 20_000
        val channel = SecureChannel(key, session, false, socket.getInputStream(), socket.getOutputStream())
        val peer = Protocol.readPeer(channel)
        check(peer.supportsDirs)
        Protocol.send(channel, "folder-test", Protocol.itemsFor(items(root), peer.features), object : TransferListener {})
    }
    private fun receive(socket: Socket, root: String) {
        socket.soTimeout = 20_000
        val channel = SecureChannel(key, session, true, socket.getInputStream(), socket.getOutputStream())
        val offer = Protocol.requestOffer(channel, "folder-test")
        val saved = SavedTree()
        Protocol.receive(channel, offer, saved.sink, object : TransferListener {})
        saved.verify(root)
    }
    private fun verifyQr(root: String) {
        repeat(2) { copy ->
            val saved = SavedTree()
            QrBundle.parse(QrBundle.build(items(root)), saved.sink)
            saved.verify(if (copy == 0) root else "$root (1)")
        }
        // A completed directory-only transfer must not retain a resume reservation.
        repeat(2) { copy ->
            val saved = SavedTree()
            QrBundle.parse(QrBundle.build(listOf(DirItem("$root-only-empty"))), saved.sink)
            val expected = if (copy == 0) "$root-only-empty" else "$root-only-empty (1)"
            check(saved.folders.single() == expected)
            check(File(baseDir(), expected).isDirectory)
        }
    }
    private fun verifyTreeWalker() {
        // Instrumentation starts the target, not the fixture APK. Explicitly start its
        // provider as shell so Android 16 also allows the first query after installation.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "content query --uri content://x.vladgba.share.test.folders/tree/root/document/root"
        )).use { it.readBytes() }
        val uri = DocumentsContract.buildTreeDocumentUri("x.vladgba.share.test.folders", "root")
        val outgoing = TreeWalker.walk(targetContext.contentResolver, uri)
        check(outgoing.map { it.info.name }.toSet() == setOf("Album", "Album/empty", "Album/nested",
            "Album/nested/deep", "Album/photo.txt", "Album/nested/photo.txt", "Album/nested/zero.bin")) {
            "Unexpected SAF tree: ${outgoing.map { it.info.name }}"
        }
        check(outgoing.count { it.info.isDir } == 4)
        check(outgoing.first().info.isDir && outgoing.first().info.name == "Album")
        check(outgoing.first { it.info.name == "Album/nested/photo.txt" }.open().use { it.readBytes().decodeToString() } == "nested photo")
    }
    @Suppress("DEPRECATION")
    private fun baseDir(): File = if (android.os.Build.VERSION.SDK_INT >= 29)
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DownloadSink.FOLDER)
    else File(targetContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DownloadSink.FOLDER)

    private inner class SavedTree {
        val folders = ArrayList<String>()
        val files = linkedMapOf<String, ByteArray>()
        val sink = DownloadSink(targetContext, onText = { error("Unexpected text") },
            onFolder = { name, _ -> folders += name },
            onFile = { name, location, uri, _ ->
                checkNotNull(uri)
                files["${location.substringAfter("pShare/")}/$name"] =
                    targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            })
        fun verify(root: String) {
            check(folders == listOf(root)) { "Wrong root: $folders; expected $root" }
            for (dir in listOf(root, "$root/empty", "$root/nested", "$root/nested/deep"))
                check(File(baseDir(), dir).isDirectory) { "Missing directory: $dir" }
            check(files.keys == setOf("$root/photo.txt", "$root/nested/photo.txt", "$root/nested/zero.bin")) { "Wrong files: ${files.keys}" }
            check(files["$root/photo.txt"]!!.decodeToString() == "root photo")
            check(files["$root/nested/photo.txt"]!!.decodeToString() == "nested photo")
            check(files["$root/nested/zero.bin"]!!.isEmpty())
        }
    }
}
