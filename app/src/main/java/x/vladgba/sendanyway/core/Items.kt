package x.vladgba.sendanyway.core

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
    /** Existing bytes on disk for this item if resuming an interrupted transfer. */
    fun offset(index: Int, info: ItemInfo): Long = 0L

    /** Seeds the checksum with the prefix bytes already on disk before resuming. */
    fun feedPrefix(index: Int, info: ItemInfo, offset: Long, crc: CRC32) {}

    fun open(index: Int, info: ItemInfo): OutputStream
    fun finish(index: Int, info: ItemInfo, ok: Boolean)
}

interface TransferListener {
    fun onProgress(done: Long, total: Long) {}
    fun onItemDone(index: Int, info: ItemInfo) {}
}

fun List<ItemInfo>.knownTotal(): Long = sumOf { if (it.size > 0) it.size else 0L }
