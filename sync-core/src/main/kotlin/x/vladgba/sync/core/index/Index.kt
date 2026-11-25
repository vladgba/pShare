package x.vladgba.sync.core.index

import x.vladgba.sync.core.fs.FileMeta
import x.vladgba.sync.core.fs.PathUtil
import x.vladgba.sync.core.fs.SyncFileSystem
import x.vladgba.sync.core.model.FileEntry
import x.vladgba.sync.core.model.VersionVector
import x.vladgba.sync.core.model.nextHistory
import x.vladgba.sync.core.rules.PathFilter
import x.vladgba.sync.core.util.AtomicFiles
import x.vladgba.sync.core.util.Hashing
import x.vladgba.sync.core.util.Json
import x.vladgba.sync.core.util.toHex
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class FolderIndex(val folderId: String, initial: Collection<FileEntry> = emptyList()) {
    val entries: ConcurrentHashMap<String, FileEntry> = ConcurrentHashMap<String, FileEntry>().apply {
        initial.forEach { put(it.path, it) }
    }

    fun snapshot(): List<FileEntry> = entries.values.toList()
}

class JsonIndexStore(private val file: File) {
    fun load(folderId: String): FolderIndex {
        if (!file.exists()) return FolderIndex(folderId)
        val o = Json.parseObject(file.readText())
        return FolderIndex(folderId, o.objList("entries").map { FileEntry.fromJson(it) })
    }

    fun save(index: FolderIndex) {
        val data = mapOf(
            "version" to 1,
            "folderId" to index.folderId,
            "entries" to index.snapshot().sortedBy { it.path }.map { it.toJson() },
        )
        AtomicFiles.write(file, Json.stringify(data).toByteArray())
    }

    fun delete() = file.delete()
}

/** Content-addressed store of small text versions, kept as merge bases for 3-way merges. */
class BlobStore(private val dir: File) {
    private fun file(hash: String) = File(dir, hash.take(2) + "/" + hash)
    fun has(hash: String) = file(hash).exists()
    fun get(hash: String): ByteArray? = file(hash).takeIf { it.exists() }?.readBytes()
    fun put(hash: String, bytes: ByteArray) {
        if (!has(hash)) AtomicFiles.write(file(hash), bytes)
    }

    fun retainOnly(keep: Set<String>) {
        dir.listFiles()?.forEach { shard ->
            shard.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        }
    }
}

/** Keeps previous versions of files before they are overwritten or deleted by sync. */
class VersionArchive(private val dir: File, private val maxFileBytes: Long = 64L * 1024 * 1024) {
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    fun archive(fs: SyncFileSystem, path: String, keep: Int) {
        if (keep <= 0) return
        val meta = fs.stat(path) ?: return
        if (meta.size > maxFileBytes) return
        val stamp = LocalDateTime.now(ZoneOffset.UTC).format(stampFormat)
        val target = File(dir, "$path~$stamp")
        target.parentFile.mkdirs()
        fs.openRead(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        prune(path, keep)
    }

    fun versionsOf(path: String): List<File> {
        val parent = File(dir, PathUtil.parent(path))
        val prefix = PathUtil.name(path) + "~"
        return parent.listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.sortedBy { it.name } ?: emptyList()
    }

    private fun prune(path: String, keep: Int) {
        val all = versionsOf(path)
        if (all.size > keep) all.take(all.size - keep).forEach { it.delete() }
    }
}

object TextDetector {
    /** Files up to this size are candidates for 3-way text merge. */
    const val MERGE_LIMIT: Long = 1L shl 20

    fun decode(bytes: ByteArray): String? {
        if (bytes.size > MERGE_LIMIT) return null
        for (b in bytes) if (b == 0.toByte()) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}

data class ScanResult(val changed: Int, val pending: Int, val files: Int, val bytes: Long)

/** Walks a folder and turns on-disk changes into new versions in the index. */
class Scanner(
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Files modified more recently than this are assumed still being written and skipped. */
    private val settleMs: Long = 2_000,
) {
    fun scan(fs: SyncFileSystem, index: FolderIndex, filter: PathFilter, blobs: BlobStore?): ScanResult {
        val listing = fs.listFiles() // throws when the folder is unavailable → nothing gets tombstoned
        val now = clock()
        val seen = HashSet<String>(listing.size * 2)
        var changed = 0
        var pending = 0
        var bytes = 0L
        for (meta in listing) {
            if (!PathUtil.isSafe(meta.path) || !filter.accepts(meta.path, meta.size)) continue
            seen += meta.path
            bytes += meta.size
            val old = index.entries[meta.path]
            if (old != null && !old.deleted && old.size == meta.size && old.modifiedAt == meta.modifiedAt) continue
            if (now - meta.modifiedAt in 0 until settleMs) {
                pending++
                continue
            }
            val hashed = try {
                hash(fs, meta)
            } catch (e: IOException) {
                pending++
                continue
            }
            if (old != null && !old.deleted && old.hash == hashed.hash) {
                index.entries[meta.path] = old.copy(size = hashed.size, modifiedAt = meta.modifiedAt)
                continue
            }
            index.entries[meta.path] = FileEntry(
                path = meta.path,
                size = hashed.size,
                modifiedAt = meta.modifiedAt,
                hash = hashed.hash,
                deleted = false,
                version = (old?.version ?: VersionVector()).increment(deviceId),
                history = old.nextHistory(),
                changedBy = deviceId,
                changedAt = now,
            )
            hashed.text?.let { blobs?.put(hashed.hash, it) }
            changed++
        }
        for (e in index.snapshot()) {
            if (e.deleted || e.path in seen || !filter.accepts(e.path, e.size)) continue
            index.entries[e.path] = e.copy(
                size = 0,
                modifiedAt = 0,
                hash = "",
                deleted = true,
                version = e.version.increment(deviceId),
                history = e.nextHistory(),
                changedBy = deviceId,
                changedAt = now,
            )
            changed++
        }
        return ScanResult(changed, pending, seen.size, bytes)
    }

    private class Hashed(val hash: String, val size: Long, val text: ByteArray?)

    private fun hash(fs: SyncFileSystem, meta: FileMeta): Hashed {
        if (meta.size <= TextDetector.MERGE_LIMIT) {
            val bytes = fs.openRead(meta.path).use { it.readBytes() }
            val isText = TextDetector.decode(bytes) != null
            return Hashed(Hashing.sha256Hex(bytes), bytes.size.toLong(), if (isText) bytes else null)
        }
        val md = Hashing.sha256()
        var size = 0L
        fs.openRead(meta.path).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                size += n
            }
        }
        return Hashed(md.digest().toHex(), size, null)
    }
}
