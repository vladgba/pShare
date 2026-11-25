package x.vladgba.share.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import x.vladgba.sync.core.fs.FileMeta
import x.vladgba.sync.core.fs.PathUtil
import x.vladgba.sync.core.fs.SyncFileSystem
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [SyncFileSystem] over the Storage Access Framework, for a folder the user picked with
 * ACTION_OPEN_DOCUMENT_TREE. No broad storage permission needed (Play-friendly).
 *
 * SAF addresses files by opaque document IDs, so we keep a path → ID cache that is rebuilt
 * on every full listing and repaired lazily when an ID turns out to be stale.
 */
class SafFileSystem(context: Context, private val treeUri: Uri) : SyncFileSystem {
    private val resolver: ContentResolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val ids = ConcurrentHashMap<String, String>().apply { put("", rootId) }

    private class Child(val id: String, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val c = try {
            resolver.query(uri, COLUMNS, null, null, null)
        } catch (e: Exception) {
            throw IOException("Can't list folder: ${e.message}", e)
        } ?: throw IOException("Can't list folder")
        return c.use {
            val out = ArrayList<Child>(it.count)
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                out += Child(
                    id = it.getString(0),
                    name = name,
                    isDir = it.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (it.isNull(3)) 0 else it.getLong(3),
                    mtime = if (it.isNull(4)) 0 else it.getLong(4),
                )
            }
            out
        }
    }

    override fun checkAvailable() {
        val ok = try {
            resolver.query(docUri(rootId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
        if (!ok) throw FileNotFoundException("Folder is not accessible any more — pick it again")
    }

    override fun listFiles(): List<FileMeta> {
        checkAvailable()
        val fresh = HashMap<String, String>()
        fresh[""] = rootId
        val out = ArrayList<FileMeta>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast("" to rootId)
        while (stack.isNotEmpty()) {
            val (prefix, id) = stack.removeLast()
            for (ch in children(id)) {
                val p = PathUtil.join(prefix, ch.name)
                fresh[p] = ch.id
                if (ch.isDir) stack.addLast(p to ch.id) else out += FileMeta(p, ch.size, ch.mtime)
            }
        }
        ids.clear()
        ids.putAll(fresh)
        return out
    }

    /** Finds the document ID for [path] by listing parents, refreshing the cache on the way. */
    private fun lookup(path: String): String? {
        if (path.isEmpty()) return rootId
        val parentPath = PathUtil.parent(path)
        val parentId = ids[parentPath]?.takeIf { exists(it) } ?: lookup(parentPath) ?: return null
        val name = PathUtil.name(path)
        var found: String? = null
        for (ch in children(parentId)) {
            ids[PathUtil.join(parentPath, ch.name)] = ch.id
            if (ch.name == name) found = ch.id
        }
        if (found == null) ids.remove(path)
        return found
    }

    private fun exists(id: String): Boolean = try {
        resolver.query(docUri(id), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (e: Exception) {
        false
    }

    private fun meta(path: String, id: String): FileMeta? = try {
        resolver.query(docUri(id), META_COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getString(0) == Document.MIME_TYPE_DIR || c.getString(3) != PathUtil.name(path)) null
            else FileMeta(path, if (c.isNull(1)) 0 else c.getLong(1), if (c.isNull(2)) 0 else c.getLong(2))
        }
    } catch (e: Exception) {
        null
    }

    /** ID of an existing regular file at [path], verifying cached IDs. */
    private fun fileId(path: String): String? {
        ids[path]?.let { id -> if (meta(path, id) != null) return id }
        val id = lookup(path) ?: return null
        return if (meta(path, id) != null) id else null
    }

    override fun stat(path: String): FileMeta? {
        require(PathUtil.isSafe(path)) { "Unsafe path: $path" }
        ids[path]?.let { id -> meta(path, id)?.let { return it } }
        val id = lookup(path) ?: return null
        return meta(path, id)
    }

    override fun openRead(path: String): InputStream {
        val id = fileId(path) ?: throw FileNotFoundException(path)
        return resolver.openInputStream(docUri(id)) ?: throw FileNotFoundException(path)
    }

    private fun ensureDir(path: String): String {
        if (path.isEmpty()) return rootId
        ids[path]?.let { if (exists(it)) return it }
        lookup(path)?.let { return it }
        val parentId = ensureDir(PathUtil.parent(path))
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), Document.MIME_TYPE_DIR, PathUtil.name(path))
            ?: throw IOException("Can't create folder $path")
        return DocumentsContract.getDocumentId(uri).also { ids[path] = it }
    }

    override fun writeAtomically(path: String, writer: (OutputStream) -> Unit): FileMeta {
        require(PathUtil.isSafe(path)) { "Unsafe path: $path" }
        checkAvailable()
        val parentId = ensureDir(PathUtil.parent(path))
        val name = PathUtil.name(path)
        val tmpUri = DocumentsContract.createDocument(
            resolver, docUri(parentId), "application/octet-stream", SyncFileSystem.TEMP_PREFIX + UUID.randomUUID()
        ) ?: throw IOException("Can't create file in ${PathUtil.parent(path).ifEmpty { "folder root" }}")
        var finalUri: Uri? = null
        try {
            (resolver.openOutputStream(tmpUri, "w") ?: throw IOException("Can't write $path")).use(writer)
            fileId(path)?.let { DocumentsContract.deleteDocument(resolver, docUri(it)) }
            finalUri = DocumentsContract.renameDocument(resolver, tmpUri, name) ?: tmpUri
            val id = DocumentsContract.getDocumentId(finalUri)
            ids[path] = id
            // Some providers sanitize names (e.g. characters FAT doesn't allow); don't leave a mismatched file.
            return meta(path, id) ?: throw IOException("Storage provider changed the name of '$name'")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, finalUri ?: tmpUri) }
            ids.remove(path)
            throw t
        }
    }

    override fun delete(path: String): Boolean {
        val id = fileId(path) ?: return false
        val ok = DocumentsContract.deleteDocument(resolver, docUri(id))
        ids.remove(path)
        // Remove directories the deletion left empty, like a desktop sync client would.
        var dir = PathUtil.parent(path)
        while (dir.isNotEmpty()) {
            val dirId = ids[dir] ?: lookup(dir) ?: break
            if (children(dirId).isNotEmpty()) break
            DocumentsContract.deleteDocument(resolver, docUri(dirId))
            ids.remove(dir)
            dir = PathUtil.parent(dir)
        }
        return ok
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        private val META_COLUMNS = arrayOf(
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_DISPLAY_NAME,
        )

        /** Human-readable location for a tree URI, e.g. "Internal storage/Documents/Notes". */
        fun describe(uri: Uri): String = try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val volume = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val vol = if (volume == "primary") "Internal storage" else volume
            if (rel.isEmpty()) vol else "$vol/$rel"
        } catch (e: Exception) {
            uri.toString()
        }
    }
}
