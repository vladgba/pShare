package x.vladgba.sync.core.net

import x.vladgba.sync.core.model.FileEntry
import x.vladgba.sync.core.util.JObj
import x.vladgba.sync.core.util.Json
import java.io.IOException

const val FRAME_MSG = 1
const val FRAME_DATA = 2

data class FolderOffer(val folderId: String, val label: String, val sends: Boolean, val accepts: Boolean) {
    fun toJson() = mapOf("id" to folderId, "label" to label, "sends" to sends, "accepts" to accepts)

    companion object {
        fun fromJson(o: JObj) = FolderOffer(o.str("id"), o.str("label"), o.bool("sends"), o.bool("accepts"))
    }
}

/** Protocol messages. Encoded as JSON objects with a "t" type tag. */
sealed class Msg {
    abstract fun body(): Map<String, Any?>
}

data class Hello(val name: String, val platform: String, val folders: List<FolderOffer>, val busy: Boolean = false) : Msg() {
    override fun body() = mapOf("name" to name, "platform" to platform, "folders" to folders.map { it.toJson() }, "busy" to busy)
}

data class PairDecision(val accept: Boolean) : Msg() {
    override fun body() = mapOf("accept" to accept)
}

data class IndexRequest(val folderId: String) : Msg() {
    override fun body() = mapOf("folderId" to folderId)
}

data class IndexPage(val folderId: String, val entries: List<FileEntry>, val last: Boolean) : Msg() {
    override fun body() = mapOf("folderId" to folderId, "entries" to entries.map { it.toJson() }, "last" to last)
}

data class FileRequest(val folderId: String, val path: String, val hash: String) : Msg() {
    override fun body() = mapOf("folderId" to folderId, "path" to path, "hash" to hash)
}

data class FileStart(val path: String, val size: Long) : Msg() {
    override fun body() = mapOf("path" to path, "size" to size)
}

data class FileEnd(val path: String, val hash: String) : Msg() {
    override fun body() = mapOf("path" to path, "hash" to hash)
}

data class FileError(val path: String, val reason: String) : Msg() {
    override fun body() = mapOf("path" to path, "reason" to reason)
}

/** "I now have this exact version" — lets MOVE_OUT folders delete their copy. */
data class Stored(val folderId: String, val path: String, val hash: String) : Msg() {
    override fun body() = mapOf("folderId" to folderId, "path" to path, "hash" to hash)
}

data object Ping : Msg() {
    override fun body() = emptyMap<String, Any?>()
}

data object Done : Msg() {
    override fun body() = emptyMap<String, Any?>()
}

object MsgCodec {
    private fun tag(m: Msg) = when (m) {
        is Hello -> "hello"
        is PairDecision -> "pair"
        is IndexRequest -> "index_req"
        is IndexPage -> "index_page"
        is FileRequest -> "file_req"
        is FileStart -> "file_start"
        is FileEnd -> "file_end"
        is FileError -> "file_err"
        is Stored -> "stored"
        Ping -> "ping"
        Done -> "done"
    }

    fun encode(m: Msg): ByteArray = Json.stringify(mapOf("t" to tag(m)) + m.body()).toByteArray()

    fun decode(bytes: ByteArray): Msg {
        val o = Json.parseObject(bytes.decodeToString())
        return when (val t = o.str("t")) {
            "hello" -> Hello(o.str("name"), o.str("platform"), o.objList("folders").map { FolderOffer.fromJson(it) }, o.bool("busy"))
            "pair" -> PairDecision(o.bool("accept"))
            "index_req" -> IndexRequest(o.str("folderId"))
            "index_page" -> IndexPage(o.str("folderId"), o.objList("entries").map { FileEntry.fromJson(it) }, o.bool("last"))
            "file_req" -> FileRequest(o.str("folderId"), o.str("path"), o.str("hash"))
            "file_start" -> FileStart(o.str("path"), o.long("size"))
            "file_end" -> FileEnd(o.str("path"), o.str("hash"))
            "file_err" -> FileError(o.str("path"), o.str("reason"))
            "stored" -> Stored(o.str("folderId"), o.str("path"), o.str("hash"))
            "ping" -> Ping
            "done" -> Done
            else -> throw IOException("Unknown message type '$t'")
        }
    }
}

fun SecureChannel.sendMsg(msg: Msg) = send(FRAME_MSG, MsgCodec.encode(msg))

fun SecureChannel.receiveMsg(): Msg {
    while (true) {
        val f = receive()
        if (f.type != FRAME_MSG) throw IOException("Expected message, got frame ${f.type}")
        val m = MsgCodec.decode(f.payload)
        if (m != Ping) return m
    }
}
