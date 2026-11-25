package x.vladgba.sync.core.sync

import x.vladgba.sync.core.fs.PathUtil
import x.vladgba.sync.core.model.FileEntry
import x.vladgba.sync.core.model.Ordering
import x.vladgba.sync.core.rules.PathFilter

sealed class Action {
    abstract val path: String

    /** Remote has a newer version of content we don't have. */
    data class Download(val local: FileEntry?, val remote: FileEntry) : Action() {
        override val path get() = remote.path
    }

    /** Remote deleted a file we still have an older version of. */
    data class Delete(val local: FileEntry, val remote: FileEntry) : Action() {
        override val path get() = remote.path
    }

    /** No content change needed, only the index entry (e.g. identical content edited on both sides). */
    data class Record(val entry: FileEntry) : Action() {
        override val path get() = entry.path
    }

    /** Both sides changed the file independently. */
    data class Conflict(val local: FileEntry, val remote: FileEntry) : Action() {
        override val path get() = remote.path
    }
}

object Planner {
    /** Decides what to do to bring [local] up to date with [remote]. Pull-only: never changes the remote. */
    fun plan(local: Map<String, FileEntry>, remote: Collection<FileEntry>, filter: PathFilter): List<Action> {
        val actions = ArrayList<Action>()
        for (r in remote) {
            if (!PathUtil.isSafe(r.path) || !filter.accepts(r.path, r.size)) continue
            val l = local[r.path]
            if (l == null) {
                actions += if (r.deleted) Action.Record(r) else Action.Download(null, r)
                continue
            }
            when (l.version.compare(r.version)) {
                // Same version but different content can only come from a non-deterministic resolution;
                // treat it as a conflict so both sides converge instead of silently diverging.
                Ordering.EQUAL -> if (!l.deleted && !r.deleted && l.hash != r.hash) actions += Action.Conflict(l, r)
                Ordering.AFTER -> Unit
                Ordering.BEFORE -> actions += when {
                    r.deleted && l.deleted -> Action.Record(r.copy(size = 0, modifiedAt = 0))
                    r.deleted -> Action.Delete(l, r)
                    !l.deleted && l.hash == r.hash -> Action.Record(r.copy(size = l.size, modifiedAt = l.modifiedAt))
                    else -> Action.Download(l, r)
                }
                Ordering.CONCURRENT -> actions += when {
                    l.deleted && r.deleted -> Action.Record(l.copy(version = l.version.merge(r.version)))
                    !l.deleted && !r.deleted && l.hash == r.hash -> Action.Record(
                        l.copy(version = l.version.merge(r.version), history = mergeHistory(l, r))
                    )
                    else -> Action.Conflict(l, r)
                }
            }
        }
        // Downloads of small files first so the folder converges quickly; big transfers last.
        return actions.sortedWith(compareBy({ it !is Action.Record }, { (it as? Action.Download)?.remote?.size ?: 0L }))
    }

    fun mergeHistory(a: FileEntry, b: FileEntry): List<String> =
        (a.history + b.history + listOf(a.hash, b.hash))
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(x.vladgba.sync.core.model.HISTORY_DEPTH)

    /** Most recent content version both sides have seen, used as the merge base. */
    fun commonAncestor(local: FileEntry, remote: FileEntry): String? {
        val mine = (local.history + local.hash).filter { it.isNotEmpty() }.toHashSet()
        return (remote.history + remote.hash).asReversed().firstOrNull { it.isNotEmpty() && it in mine }
    }
}
