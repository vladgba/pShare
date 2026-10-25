package x.vladgba.sendanyway.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import x.vladgba.sendanyway.core.DirItem
import x.vladgba.sendanyway.core.ItemInfo
import x.vladgba.sendanyway.core.OutgoingItem
import x.vladgba.sendanyway.core.ReceiveSink
import x.vladgba.sendanyway.core.ReceivedPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/** A file picked in the system picker or shared from another app. */
class UriItem(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val overrideName: String? = null,
) : OutgoingItem {
    override val info: ItemInfo

    init {
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        info = ItemInfo(
            ItemInfo.KIND_FILE,
            overrideName ?: name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file",
            resolver.getType(uri) ?: "application/octet-stream",
            size,
        )
    }

    @Volatile private var opened: InputStream? = null

    /**
     * Opens the file now. Read access to shared URIs can end when the sending screen is closed;
     * a stream opened while access was granted stays readable, so the transfer can continue in
     * the background.
     */
    fun preopen() {
        if (opened == null) opened = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
    }

    override fun open(): InputStream =
        opened?.also { opened = null } ?: resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
}

/**
 * Recursively traverses document trees picked via ACTION_OPEN_DOCUMENT_TREE. The picked folder
 * itself is the first entry ("Album"), followed depth-first by a [DirItem] for every subfolder
 * (empty ones too) and a [UriItem] for every file, all named by their path from the picked
 * folder's parent ("Album/2024/photo.jpg"). Each folder precedes its contents.
 */
object TreeWalker {
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    fun walk(resolver: ContentResolver, treeUri: Uri): List<OutgoingItem> {
        val out = ArrayList<OutgoingItem>()
        val rootDocId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return out }
        val rootName = DownloadSink.safeSegment(rootName(resolver, treeUri, rootDocId))
        fun traverse(docId: String, currentPath: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val childId = if (idIdx >= 0) c.getString(idIdx) ?: continue else continue
                    val displayName = DownloadSink.safeSegment(if (nameIdx >= 0) c.getString(nameIdx) ?: "file" else "file")
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                    val path = "$currentPath/$displayName"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        out.add(DirItem(path))
                        traverse(childId, path)
                    } else {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        out.add(UriItem(resolver, docUri, overrideName = path))
                    }
                }
            }
        }
        out.add(DirItem(rootName))
        traverse(rootDocId, rootName)
        return out
    }

    /** Display name of the picked folder, falling back to the last part of its document id. */
    private fun rootName(resolver: ContentResolver, treeUri: Uri, rootDocId: String): String {
        try {
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {}
        return rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "folder" }
    }
}

object Device {
    fun name(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}

/**
 * Saves received files to Download/SendAnyway (MediaStore on Android 10+, no permission needed;
 * the app's own external files dir on older versions). Text items are kept in memory.
 * Received folders are recreated with their hierarchy, empty ones included; a folder whose name
 * is taken is saved as "Name (1)" as a whole (see [ReceivedPaths]). Supports resuming interrupted
 * downloads via .part files; a resumed transfer reuses the folder its first attempt chose.
 * Callbacks are invoked on the worker thread.
 */
class DownloadSink(
    ctx: Context,
    private val onText: (String) -> Unit,
    private val onFile: (name: String, location: String, uri: Uri?, mime: String) -> Unit,
    private val onFolder: (name: String, location: String) -> Unit = { _, _ -> },
) : ReceiveSink {
    private val app = ctx.applicationContext
    private val resolver = app.contentResolver
    private val stagingDir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, ".staging").apply { mkdirs() }
    private val texts = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val paths = ReceivedPaths(::rootExists)

    /** Remembers the root folders chosen for an unfinished transfer, so a retry resumes into them. */
    private var rootsFile: File? = null

    private val scoped get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Real directory behind Download/SendAnyway: the public one on Android 10+, app-specific before. */
    @Suppress("DEPRECATION")
    private val baseDir: File
        get() = if (scoped) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        else File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, FOLDER)

    private fun location(relDir: String): String = when {
        !scoped -> (if (relDir.isEmpty()) baseDir else File(baseDir, relDir)).absolutePath
        relDir.isEmpty() -> "Download/$FOLDER"
        else -> "Download/$FOLDER/$relDir"
    }

    private fun relativePath(relDir: String) =
        if (relDir.isEmpty()) "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER" else "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$relDir"

    /** Keyed by the full sender path, so equal names in different subfolders never share one. */
    private fun partFile(info: ItemInfo): File {
        val key = CRC32().apply { update(info.name.toByteArray(Charsets.UTF_8)) }.value
        val name = ReceivedPaths.segments(info.name).last()
        return File(stagingDir, "$name.${java.lang.Long.toHexString(key)}.${info.size}.part")
    }

    override fun begin(items: List<ItemInfo>) {
        val sig = CRC32()
        items.forEach { sig.update("${it.kind}/${it.size}/${it.name}\n".toByteArray(Charsets.UTF_8)) }
        val file = File(stagingDir, "roots-${java.lang.Long.toHexString(sig.value)}.txt")
        rootsFile = file
        try {
            if (file.exists()) file.readLines().forEach { line ->
                val kv = line.split('\t')
                if (kv.size == 2) paths.assign(kv[0], kv[1])
            }
        } catch (_: Exception) {}
        items.mapNotNullTo(LinkedHashSet()) { paths.rootOf(it) }.forEach { paths.rootFor(it) }
        if (paths.assigned().isNotEmpty()) try {
            file.writeText(paths.assigned().entries.joinToString("\n") { "${it.key}\t${it.value}" })
        } catch (_: Exception) {}
    }

    override fun complete() {
        rootsFile?.delete()
    }

    /** Whether a top-level folder [name] already exists at the destination. */
    private fun rootExists(name: String): Boolean {
        if (File(baseDir, name).exists()) return true
        if (!scoped) return false
        // Android 10 may hide the public folder from the File API; our own earlier files are still visible here.
        return try {
            val prefix = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name/"
            val like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'", arrayOf(like), null,
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun offset(index: Int, info: ItemInfo): Long {
        if (info.isText || info.isDir || info.size <= 0) return 0L
        val p = partFile(info)
        if (p.exists()) {
            val len = p.length()
            if (len in 1 until info.size) return len
            if (len >= info.size) p.delete()
        }
        return 0L
    }

    override fun feedPrefix(index: Int, info: ItemInfo, offset: Long, crc: CRC32) {
        if (offset <= 0) return
        val p = partFile(info)
        if (!p.exists()) return
        val buf = ByteArray(64 * 1024)
        p.inputStream().use { inp ->
            var left = offset
            while (left > 0) {
                val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                crc.update(buf, 0, r)
                left -= r
            }
        }
    }

    override fun open(index: Int, info: ItemInfo): OutputStream {
        if (info.isText) return ByteArrayOutputStream().also { texts[index] = it }
        if (info.isDir) return object : OutputStream() { override fun write(b: Int) {} }
        val p = partFile(info)
        val appending = p.exists() && p.length() > 0 && p.length() < info.size
        return FileOutputStream(p, appending)
    }

    override fun finish(index: Int, info: ItemInfo, ok: Boolean) {
        texts.remove(index)?.let { if (ok) onText(it.toString("UTF-8")); return }
        // On failure the .part file stays, so a subsequent retry can resume.
        if (!ok) return
        val parts = paths.map(info)
        if (info.isDir) {
            makeDir(parts.joinToString("/"))
            if (parts.size == 1) onFolder(parts[0], location(""))
            return
        }
        saveFile(info, parts.dropLast(1).joinToString("/"), parts.last())
    }

    private fun saveFile(info: ItemInfo, relDir: String, fileName: String) {
        val p = partFile(info)
        if (scoped) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, info.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath(relDir))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("cannot create $fileName")
            try {
                p.inputStream().use { inp ->
                    resolver.openOutputStream(uri)?.use { out -> inp.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                p.delete()
                onFile(fileName, location(relDir), uri, info.mime)
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
            return
        }

        val targetDir = (if (relDir.isEmpty()) baseDir else File(baseDir, relDir)).apply { mkdirs() }
        val finalFile = unique(targetDir, fileName)
        if (!p.renameTo(finalFile)) {
            p.inputStream().use { inp -> finalFile.outputStream().use { out -> inp.copyTo(out) } }
            p.delete()
        }
        onFile(finalFile.name, targetDir.absolutePath, Uri.fromFile(finalFile), info.mime)
    }

    /**
     * Creates a received folder, also when it stays empty. Android 11+ lets any app create folders
     * in the public Download directory through the File API, as does every version for the
     * app-specific directory. Android 10 doesn't: there a placeholder file inserted through
     * MediaStore makes the provider create the folder, which stays after the placeholder is deleted.
     */
    private fun makeDir(relDir: String) {
        val dir = File(baseDir, relDir)
        if (dir.isDirectory || dir.mkdirs() || dir.isDirectory) return
        if (!scoped) throw IOException("cannot create folder $relDir")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, ".sendanyway-folder")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath(relDir))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("cannot create folder $relDir")
        try {
            resolver.openOutputStream(uri)?.close()
        } finally {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
        }
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    companion object {
        const val FOLDER = "SendAnyway"

        fun safeSegment(name: String): String = ReceivedPaths.safeSegment(name)
    }
}
