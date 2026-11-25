package x.vladgba.sync.core.sync

import x.vladgba.sync.core.model.FolderConfig
import x.vladgba.sync.core.model.PeerInfo
import x.vladgba.sync.core.util.AtomicFiles
import x.vladgba.sync.core.util.JObj
import x.vladgba.sync.core.util.Json
import x.vladgba.sync.core.util.Observable
import java.io.File

/** A list persisted as a JSON array and observable. */
open class JsonListStore<T>(
    private val file: File,
    private val encode: (T) -> Map<String, Any?>,
    private val decode: (JObj) -> T,
) {
    val items = Observable(load())

    private fun load(): List<T> = try {
        if (!file.exists()) emptyList()
        else (Json.parse(file.readText()) as List<*>).mapNotNull { m ->
            @Suppress("UNCHECKED_CAST")
            runCatching { decode(JObj(m as Map<String, Any?>)) }.getOrNull()
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun update(transform: (List<T>) -> List<T>) {
        val next = transform(items.value)
        AtomicFiles.write(file, Json.stringify(next.map(encode)).toByteArray())
        items.set(next)
    }
}

class FolderConfigStore(file: File) : JsonListStore<FolderConfig>(file, { it.toJson() }, FolderConfig::fromJson) {
    fun upsert(c: FolderConfig) = update { list ->
        if (list.any { it.folderId == c.folderId }) list.map { if (it.folderId == c.folderId) c else it } else list + c
    }

    fun remove(folderId: String) = update { list -> list.filter { it.folderId != folderId } }
}

class PeerStore(file: File) : JsonListStore<PeerInfo>(file, { it.toJson() }, PeerInfo::fromJson) {
    fun get(deviceId: String) = items.value.firstOrNull { it.deviceId == deviceId }
    fun upsert(p: PeerInfo) = update { list -> list.filter { it.deviceId != p.deviceId } + p }
    fun remove(deviceId: String) = update { list -> list.filter { it.deviceId != deviceId } }
    fun modify(deviceId: String, f: (PeerInfo) -> PeerInfo) =
        update { list -> list.map { if (it.deviceId == deviceId) f(it) else it } }
}

enum class LogLevel { INFO, WARN, ERROR }
data class LogLine(val time: Long, val level: LogLevel, val text: String)

class EventLog(private val capacity: Int = 300, private val sink: (LogLine) -> Unit = {}) {
    val lines = Observable<List<LogLine>>(emptyList())

    fun info(text: String) = add(LogLevel.INFO, text)
    fun warn(text: String) = add(LogLevel.WARN, text)
    fun error(text: String) = add(LogLevel.ERROR, text)

    private fun add(level: LogLevel, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, text)
        lines.update { (it + line).takeLast(capacity) }
        sink(line)
    }
}
