package x.vladgba.sync.core.sync

import x.vladgba.sync.core.fs.PathUtil
import x.vladgba.sync.core.model.FileEntry
import x.vladgba.sync.core.model.SyncDirection
import x.vladgba.sync.core.net.Done
import x.vladgba.sync.core.net.FRAME_DATA
import x.vladgba.sync.core.net.FRAME_MSG
import x.vladgba.sync.core.net.FileEnd
import x.vladgba.sync.core.net.FileError
import x.vladgba.sync.core.net.FileRequest
import x.vladgba.sync.core.net.FileStart
import x.vladgba.sync.core.net.FolderOffer
import x.vladgba.sync.core.net.IndexPage
import x.vladgba.sync.core.net.IndexRequest
import x.vladgba.sync.core.net.MsgCodec
import x.vladgba.sync.core.net.Ping
import x.vladgba.sync.core.net.SecureChannel
import x.vladgba.sync.core.net.Stored
import x.vladgba.sync.core.net.sendMsg
import x.vladgba.sync.core.util.Hashing
import x.vladgba.sync.core.util.toHex
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

data class SessionSummary(
    val received: Int = 0,
    val receivedBytes: Long = 0,
    val deleted: Int = 0,
    val merged: Int = 0,
    val conflicts: Int = 0,
    val skipped: Int = 0,
    val sent: Int = 0,
    val movedOut: Int = 0,
) {
    val isEmpty get() = this == SessionSummary()
    override fun toString() = buildList {
        if (received > 0) add("↓$received (${formatBytes(receivedBytes)})")
        if (sent > 0) add("↑$sent")
        if (deleted > 0) add("deleted $deleted")
        if (merged > 0) add("merged $merged")
        if (conflicts > 0) add("conflicts $conflicts")
        if (movedOut > 0) add("moved $movedOut")
        if (skipped > 0) add("skipped $skipped")
    }.joinToString(", ").ifEmpty { "up to date" }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}

/**
 * One sync exchange with a peer. Both sides run the same code: each pulls what it needs
 * from the other (request/response) while serving the other's requests. When both have
 * sent [Done] the connection is closed.
 *
 * Threads: the caller pulls; a reader thread demultiplexes incoming frames; a server thread
 * answers the peer's requests one at a time. Each side has at most one request in flight,
 * so data frames in each direction always belong to the single current transfer.
 */
internal class SyncSession(
    private val ch: SecureChannel,
    private val local: Map<String, FolderRuntime>,
    private val remote: Map<String, FolderOffer>,
    private val log: EventLog,
) {
    private class Failure(val error: IOException)
    private object EndOfQueue

    private val responses = ArrayBlockingQueue<Any>(32)
    private val serveQueue = LinkedBlockingQueue<Any>()
    private val peerDone = CompletableFuture<Unit>()
    @Volatile private var closing = false
    private var summary = SessionSummary()
    @Volatile private var sentCount = 0
    @Volatile private var movedCount = 0

    fun run(): SessionSummary {
        val reader = thread(name = "lb-session-rx", isDaemon = true) { readLoop() }
        val server = thread(name = "lb-session-serve", isDaemon = true) { serveLoop() }
        val heartbeat = thread(name = "lb-session-ping", isDaemon = true) {
            try {
                while (!closing) {
                    Thread.sleep(20_000)
                    if (!closing) runCatching { ch.sendMsg(Ping) }
                }
            } catch (_: InterruptedException) {
            }
        }
        try {
            for ((id, folder) in local) {
                val offer = remote[id] ?: continue
                if (folder.config.direction.accepts && offer.sends && !folder.config.paused) pullFolder(folder)
            }
            ch.sendMsg(Done)
            try {
                peerDone.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            serveQueue.put(EndOfQueue)
            server.join()
        } finally {
            closing = true
            heartbeat.interrupt()
            ch.close()
            serveQueue.put(EndOfQueue)
            reader.join(2_000)
            server.join(2_000)
            local.values.forEach { runCatching { it.save() } }
        }
        return summary.copy(sent = sentCount, movedOut = movedCount)
    }

    private fun readLoop() {
        try {
            while (true) {
                val f = ch.receive()
                when (f.type) {
                    FRAME_DATA -> responses.put(f.payload)
                    FRAME_MSG -> when (val m = MsgCodec.decode(f.payload)) {
                        is IndexRequest, is FileRequest, is Stored -> serveQueue.put(m)
                        is IndexPage, is FileStart, is FileEnd, is FileError -> responses.put(m)
                        Done -> peerDone.complete(Unit)
                        else -> Unit
                    }
                    else -> throw IOException("Unknown frame type ${f.type}")
                }
            }
        } catch (e: Exception) {
            val err = e as? IOException ?: IOException(e.message, e)
            responses.clear()
            responses.offer(Failure(err))
            serveQueue.put(EndOfQueue)
            peerDone.completeExceptionally(err)
        }
    }

    private fun nextResponse(): Any {
        val r = responses.take()
        if (r is Failure) {
            responses.offer(r) // keep failing for any later caller
            throw r.error
        }
        return r
    }

    // ---------------------------------------------------------------- pulling

    private fun pullFolder(folder: FolderRuntime) {
        val remoteEntries = requestIndex(folder.id)
        folder.lock.withLock {
            val plan = Planner.plan(folder.index.entries, remoteEntries, folder.filter)
            if (plan.isEmpty()) return
            val applier = ChangeApplier(folder)
            var sinceSave = 0
            for (action in plan) {
                val result = try {
                    applier.apply(action) { entry -> download(folder, entry) }
                } catch (e: TransferException) {
                    ApplyResult.Skipped(action.path, e.message ?: "transfer failed")
                }
                record(folder, result)
                if (++sinceSave >= 100) {
                    folder.save(); sinceSave = 0
                }
            }
            folder.save()
            folder.updateStats { it.copy(lastSync = System.currentTimeMillis()) }
        }
    }

    private fun record(folder: FolderRuntime, r: ApplyResult) {
        summary = when (r) {
            is ApplyResult.Stored -> {
                ch.sendMsg(Stored(folder.id, r.path, r.hash))
                summary.copy(received = summary.received + 1)
            }
            is ApplyResult.Deleted -> summary.copy(deleted = summary.deleted + 1)
            is ApplyResult.Merged -> {
                log.info("${folder.config.label}: merged concurrent edits in ${r.path}")
                summary.copy(merged = summary.merged + 1)
            }
            is ApplyResult.KeptBoth -> {
                log.warn("${folder.config.label}: conflict in ${r.path}, other version saved as ${PathUtil.name(r.conflictCopy)}")
                summary.copy(conflicts = summary.conflicts + 1)
            }
            is ApplyResult.Skipped -> {
                log.warn("${folder.config.label}: skipped ${r.path} — ${r.reason}")
                summary.copy(skipped = summary.skipped + 1)
            }
            ApplyResult.Recorded -> summary
        }
    }

    private fun requestIndex(folderId: String): List<FileEntry> {
        ch.sendMsg(IndexRequest(folderId))
        val out = ArrayList<FileEntry>()
        while (true) {
            when (val r = nextResponse()) {
                is IndexPage -> {
                    out += r.entries
                    if (r.last) return out
                }
                else -> throw IOException("Unexpected response to index request: $r")
            }
        }
    }

    private fun download(folder: FolderRuntime, entry: FileEntry): File {
        ch.sendMsg(FileRequest(folder.id, entry.path, entry.hash))
        when (val first = nextResponse()) {
            is FileError -> throw TransferException(first.reason)
            is FileStart -> Unit
            else -> throw IOException("Unexpected response to file request: $first")
        }
        val tmp = File(folder.staging, UUID.randomUUID().toString())
        val md = Hashing.sha256()
        var bytes = 0L
        var remoteError: String? = null
        try {
            tmp.outputStream().buffered(256 * 1024).use { out ->
                loop@ while (true) {
                    when (val r = nextResponse()) {
                        is ByteArray -> {
                            md.update(r); out.write(r); bytes += r.size
                        }
                        is FileEnd -> break@loop
                        is FileError -> {
                            remoteError = r.reason; break@loop
                        }
                        else -> throw IOException("Unexpected frame during transfer: $r")
                    }
                }
            }
            remoteError?.let { throw TransferException(it) }
            if (md.digest().toHex() != entry.hash) throw TransferException("content changed on peer")
            summary = summary.copy(receivedBytes = summary.receivedBytes + bytes)
            return tmp
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    // ---------------------------------------------------------------- serving

    private fun serveLoop() {
        while (true) {
            val m = serveQueue.take()
            if (m === EndOfQueue) return
            try {
                when (m) {
                    is IndexRequest -> serveIndex(m)
                    is FileRequest -> serveFile(m)
                    is Stored -> onStored(m)
                }
            } catch (e: IOException) {
                ch.close() // broken connection; the reader will notice and fail the session
                return
            }
        }
    }

    private fun servable(folderId: String): FolderRuntime? =
        local[folderId]?.takeIf { it.config.direction.sends && !it.config.paused }

    private fun serveIndex(req: IndexRequest) {
        val entries = servable(req.folderId)?.index?.snapshot() ?: emptyList()
        if (entries.isEmpty()) {
            ch.sendMsg(IndexPage(req.folderId, emptyList(), true))
            return
        }
        val pages = entries.chunked(500)
        pages.forEachIndexed { i, page -> ch.sendMsg(IndexPage(req.folderId, page, i == pages.lastIndex)) }
    }

    private fun serveFile(req: FileRequest) {
        val folder = servable(req.folderId)
        val entry = folder?.index?.entries?.get(req.path)
        val stat = if (folder != null && PathUtil.isSafe(req.path)) folder.fs.stat(req.path) else null
        if (folder == null || entry == null || entry.deleted || entry.hash != req.hash || stat == null) {
            ch.sendMsg(FileError(req.path, "not available"))
            return
        }
        val input = try {
            folder.fs.openRead(req.path)
        } catch (e: IOException) {
            ch.sendMsg(FileError(req.path, "read failed: ${e.message}"))
            return
        }
        val md = Hashing.sha256()
        // Read errors are reported to the peer; send errors (broken connection) propagate.
        input.use {
            ch.sendMsg(FileStart(req.path, stat.size))
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    ch.sendMsg(FileError(req.path, "read failed: ${e.message}"))
                    return
                }
                if (n < 0) break
                md.update(buf, 0, n)
                ch.send(FRAME_DATA, buf, 0, n)
            }
        }
        ch.sendMsg(FileEnd(req.path, md.digest().toHex()))
        sentCount++
    }

    private fun onStored(m: Stored) {
        val folder = local[m.folderId] ?: return
        if (folder.config.direction != SyncDirection.MOVE_OUT) return
        folder.lock.withLock {
            val e = folder.index.entries[m.path] ?: return
            if (e.deleted || e.hash != m.hash) return
            val st = folder.fs.stat(m.path) ?: return
            if (st.size != e.size || st.modifiedAt != e.modifiedAt) return
            if (folder.fs.delete(m.path)) {
                folder.index.entries.remove(m.path) // no tombstone: the peer keeps its copy
                movedCount++
            }
        }
    }

    companion object {
        const val CHUNK = 256 * 1024
    }
}
