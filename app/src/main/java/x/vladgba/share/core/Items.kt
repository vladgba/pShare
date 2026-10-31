package x.vladgba.share.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Description of one transferred item. [size] is -1 when unknown up front (stream transports only).
 * [name] is a '/'-separated path relative to the transfer root; for a picked folder it starts with
 * the folder's own name ("Album/photo.jpg"). [KIND_DIR] entries describe a folder (including empty
 * ones), carry no payload and always precede their contents.
 */
class ItemInfo(val kind: Int, val name: String, val mime: String, val size: Long) {
    val isText: Boolean get() = kind == KIND_TEXT
    val isDir: Boolean get() = kind == KIND_DIR

    fun write(out: DataOutputStream) {
        out.writeByte(kind)
        out.writeStr(name)
        out.writeStr(mime, 255)
        out.writeLong(size)
    }

    fun withSize(newSize: Long) = ItemInfo(kind, name, mime, newSize)

    companion object {
        const val KIND_FILE = 0
        const val KIND_TEXT = 1
        /** Folder entry. Only sent to peers that announced [Protocol.FEATURE_DIRS]. */
        const val KIND_DIR = 2
        const val MIME_DIR = "inode/directory"

        fun read(inp: DataInputStream) = ItemInfo(
            kind = inp.readUnsignedByte(),
            name = inp.readStr(),
            mime = inp.readStr(),
            size = inp.readLong(),
        )
    }
}

/** Something the sender can stream. Implementations may open the data lazily. */
interface OutgoingItem {
    val info: ItemInfo
    fun open(): InputStream
}

class TextItem(text: String) : OutgoingItem {
    private val bytes = text.toByteArray(Charsets.UTF_8)
    override val info = ItemInfo(ItemInfo.KIND_TEXT, "text.txt", "text/plain", bytes.size.toLong())
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** A folder entry; [path] is relative to the transfer root, e.g. "Album" or "Album/2024". */
class DirItem(path: String) : OutgoingItem {
    override val info = ItemInfo(ItemInfo.KIND_DIR, path, ItemInfo.MIME_DIR, 0L)
    override fun open(): InputStream = ByteArrayInputStream(ByteArray(0))
}

/** Where received items go. [finish] is always called after [open], with ok=false on failure. */
interface ReceiveSink {
    /** Called once with the whole offer before any other call. */
    fun begin(items: List<ItemInfo>) {}

    /** Called after every item has been successfully received. */
    fun complete() {}

    /** Existing bytes on disk for this item if resuming an interrupted transfer. */
    fun offset(index: Int, info: ItemInfo): Long = 0L

    /** Seeds the checksum with the prefix bytes already on disk before resuming. */
    fun feedPrefix(index: Int, info: ItemInfo, offset: Long, crc: CRC32) {}

    fun open(index: Int, info: ItemInfo): OutputStream
    fun finish(index: Int, info: ItemInfo, ok: Boolean)
}

/**
 * Where received paths land. Sender paths are untrusted: [segments] drops empty, "." and ".."
 * parts and makes each part a safe file name, so nothing can escape the destination folder.
 *
 * A received folder tree keeps its hierarchy under one top-level folder ("Album/..."). If that
 * folder already exists at the destination, the whole tree goes to "Album (1)" instead, so
 * a transfer never merges into an older one. Single top-level files are left as they are.
 */
class ReceivedPaths(private val exists: (root: String) -> Boolean) {
    private val roots = HashMap<String, String>()

    /** Root folders already chosen, e.g. restored for a resumed transfer: received name to destination name. */
    fun assigned(): Map<String, String> = roots

    fun assign(root: String, destination: String) { roots[root] = destination }

    /** Safe destination path segments of [info], with the top-level folder renamed if needed. */
    fun map(info: ItemInfo): List<String> {
        val parts = segments(info.name)
        if (!info.isDir && parts.size == 1) return parts
        return listOf(rootFor(parts[0])) + parts.drop(1)
    }

    /** Top-level folder [info] belongs to (null for single top-level files). */
    fun rootOf(info: ItemInfo): String? {
        val parts = segments(info.name)
        return if (!info.isDir && parts.size == 1) null else parts[0]
    }

    fun rootFor(root: String): String = roots.getOrPut(root) {
        val taken = roots.values.toSet()
        var name = root
        var n = 1
        while (name in taken || exists(name)) name = "$root (${n++})"
        name
    }

    companion object {
        fun segments(path: String): List<String> {
            val parts = path.replace('\\', '/').split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { safeSegment(it) }
            return parts.ifEmpty { listOf("file") }
        }

        fun safeSegment(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(120).ifEmpty { "file" }
    }
}

interface TransferListener {
    fun onProgress(done: Long, total: Long) {}
    fun onItemDone(index: Int, info: ItemInfo) {}
}

fun List<ItemInfo>.knownTotal(): Long = sumOf { if (it.size > 0) it.size else 0L }
