package x.vladgba.sync.core.sync

import x.vladgba.sync.core.index.TextDetector
import x.vladgba.sync.core.merge.ConflictNaming
import x.vladgba.sync.core.merge.Diff3
import x.vladgba.sync.core.model.FileEntry
import x.vladgba.sync.core.util.Hashing
import java.io.File

/** Fetches a remote file into a local staging file whose content hash has been verified. */
typealias Fetcher = (FileEntry) -> File

class TransferException(message: String) : Exception(message)

sealed class ApplyResult {
    data class Stored(val path: String, val hash: String) : ApplyResult()
    data class Merged(val path: String) : ApplyResult()
    data class KeptBoth(val path: String, val conflictCopy: String) : ApplyResult()
    data class Deleted(val path: String) : ApplyResult()
    data object Recorded : ApplyResult()
    data class Skipped(val path: String, val reason: String) : ApplyResult()
}

/**
 * Applies planned actions to one folder. Must be called with the folder lock held.
 *
 * Resolutions are deterministic: both devices resolving the same conflict produce the same
 * content and the same version vector, so they converge without another round of conflicts.
 */
class ChangeApplier(private val folder: FolderRuntime) {
    private val fs get() = folder.fs
    private val entries get() = folder.index.entries

    fun apply(action: Action, fetch: Fetcher): ApplyResult = when (action) {
        is Action.Record -> {
            entries[action.entry.path] = action.entry
            ApplyResult.Recorded
        }
        is Action.Download -> download(action.local, action.remote, fetch)
        is Action.Delete -> delete(action.local, action.remote)
        is Action.Conflict -> conflict(action.local, action.remote, fetch)
    }

    /** True if the file on disk is still what the index says (no unscanned local edit). */
    private fun localMatches(entry: FileEntry?, path: String): Boolean {
        val st = fs.stat(path)
        return if (entry == null || entry.deleted) st == null
        else st != null && st.size == entry.size && st.modifiedAt == entry.modifiedAt
    }

    private fun archive(path: String) {
        if (fs.stat(path) != null) folder.archive.archive(fs, path, folder.config.keepVersions)
    }

    private fun writeFrom(path: String, src: File) =
        fs.writeAtomically(path) { out -> src.inputStream().use { it.copyTo(out, 64 * 1024) } }

    private fun rememberText(hash: String, file: File) {
        if (file.length() > TextDetector.MERGE_LIMIT) return
        val bytes = file.readBytes()
        if (TextDetector.decode(bytes) != null) folder.blobs.put(hash, bytes)
    }

    private fun download(local: FileEntry?, remote: FileEntry, fetch: Fetcher): ApplyResult {
        val staged = fetch(remote)
        try {
            if (!localMatches(local, remote.path)) return ApplyResult.Skipped(remote.path, "changed locally during sync")
            archive(remote.path)
            val meta = writeFrom(remote.path, staged)
            rememberText(remote.hash, staged)
            entries[remote.path] = remote.copy(size = meta.size, modifiedAt = meta.modifiedAt)
            return ApplyResult.Stored(remote.path, remote.hash)
        } finally {
            staged.delete()
        }
    }

    private fun delete(local: FileEntry, remote: FileEntry): ApplyResult {
        if (!localMatches(local, local.path)) return ApplyResult.Skipped(local.path, "changed locally during sync")
        archive(local.path)
        fs.delete(local.path)
        entries[local.path] = remote.copy(size = 0, modifiedAt = 0)
        return ApplyResult.Deleted(local.path)
    }

    private fun conflict(local: FileEntry, remote: FileEntry, fetch: Fetcher): ApplyResult {
        val merged = local.version.merge(remote.version)
        val history = Planner.mergeHistory(local, remote)

        // Deleted on one side, edited on the other: the edit wins.
        if (remote.deleted) {
            entries[local.path] = local.copy(version = merged)
            return ApplyResult.Recorded
        }
        if (local.deleted) {
            val r = download(local, remote, fetch)
            if (r is ApplyResult.Stored) entries[remote.path] = entries.getValue(remote.path).copy(version = merged)
            return r
        }

        val staged = fetch(remote)
        try {
            if (!localMatches(local, local.path)) return ApplyResult.Skipped(local.path, "changed locally during sync")

            val divergedEqual = local.version == remote.version
            (if (divergedEqual) null else tryTextMerge(local, remote, staged))?.let { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                val hash = Hashing.sha256Hex(bytes)
                archive(local.path)
                val meta = fs.writeAtomically(local.path) { it.write(bytes) }
                folder.blobs.put(hash, bytes)
                entries[local.path] = FileEntry(
                    path = local.path, size = meta.size, modifiedAt = meta.modifiedAt, hash = hash,
                    version = merged, history = history,
                    changedBy = maxOf(local.changedBy, remote.changedBy),
                    changedAt = maxOf(local.changedAt, remote.changedAt),
                )
                return ApplyResult.Merged(local.path)
            }

            // Keep both: the newer edit keeps the name, the other becomes a conflict copy.
            val localWins = if (divergedEqual) local.hash > remote.hash
            else compareValuesBy(local, remote, { it.changedAt }, { it.changedBy }, { it.hash }) > 0
            val loser = if (localWins) local else remote
            val copyPath = ConflictNaming.conflictPath(local.path, loser.changedBy, loser.changedAt)
            val copyMeta = if (localWins) {
                writeFrom(copyPath, staged)
            } else {
                fs.writeAtomically(copyPath) { out -> fs.openRead(local.path).use { it.copyTo(out, 64 * 1024) } }
            }
            entries[copyPath] = loser.copy(path = copyPath, size = copyMeta.size, modifiedAt = copyMeta.modifiedAt, history = emptyList())
            if (localWins) {
                entries[local.path] = local.copy(version = merged, history = history)
            } else {
                archive(local.path)
                val meta = writeFrom(local.path, staged)
                rememberText(remote.hash, staged)
                entries[local.path] = remote.copy(size = meta.size, modifiedAt = meta.modifiedAt, version = merged, history = history)
            }
            folder.updateStats { it.copy(conflicts = it.conflicts + 1) }
            return ApplyResult.KeptBoth(local.path, copyPath)
        } finally {
            staged.delete()
        }
    }

    private fun tryTextMerge(local: FileEntry, remote: FileEntry, staged: File): String? {
        if (local.size > TextDetector.MERGE_LIMIT || remote.size > TextDetector.MERGE_LIMIT) return null
        val baseHash = Planner.commonAncestor(local, remote) ?: return null
        val base = folder.blobs.get(baseHash)?.let { TextDetector.decode(it) } ?: return null
        val localBytes = fs.openRead(local.path).use { it.readBytes() }
        if (Hashing.sha256Hex(localBytes) != local.hash) return null
        val ours = TextDetector.decode(localBytes) ?: return null
        val theirs = TextDetector.decode(staged.readBytes()) ?: return null
        return Diff3.merge(base, ours, theirs)
    }
}
