package x.vladgba.sync.core.fs

import x.vladgba.sync.core.util.AtomicFiles
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID

data class FileMeta(val path: String, val size: Long, val modifiedAt: Long)

/**
 * Minimal file access the sync engine needs. Paths are relative, '/'-separated,
 * validated with [PathUtil.isSafe]. Android implements this over SAF, desktop over java.io.
 */
interface SyncFileSystem {
    /** Throws if the folder root is gone or inaccessible (unplugged drive, revoked permission). */
    fun checkAvailable()

    /** All regular files, recursively. */
    fun listFiles(): List<FileMeta>
    fun stat(path: String): FileMeta?
    fun openRead(path: String): InputStream

    /**
     * Writes content produced by [writer] to a temp file next to the target and then replaces
     * the target. If [writer] throws, the target is untouched.
     */
    fun writeAtomically(path: String, writer: (OutputStream) -> Unit): FileMeta
    fun delete(path: String): Boolean

    companion object {
        const val TEMP_PREFIX = ".lbtmp-"
    }
}

object PathUtil {
    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.length > 1024) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains('\u0000') || path.contains(':')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun name(path: String): String = path.substringAfterLast('/')
    fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
}

/** Plain java.io implementation, used on desktop and in tests. */
class LocalFileSystem(private val root: File) : SyncFileSystem {

    override fun checkAvailable() {
        if (!root.isDirectory) throw FileNotFoundException("Folder not available: $root")
    }

    override fun listFiles(): List<FileMeta> {
        checkAvailable()
        val out = ArrayList<FileMeta>()
        root.walkTopDown()
            .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
            .forEach { f ->
                if (f.isFile && !Files.isSymbolicLink(f.toPath())) {
                    out += FileMeta(f.relativeTo(root).invariantSeparatorsPath, f.length(), f.lastModified())
                }
            }
        return out
    }

    private fun resolve(path: String): File {
        require(PathUtil.isSafe(path)) { "Unsafe path: $path" }
        return File(root, path)
    }

    override fun stat(path: String): FileMeta? =
        resolve(path).takeIf { it.isFile }?.let { FileMeta(path, it.length(), it.lastModified()) }

    override fun openRead(path: String): InputStream = FileInputStream(resolve(path))

    override fun writeAtomically(path: String, writer: (OutputStream) -> Unit): FileMeta {
        checkAvailable()
        val target = resolve(path)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, SyncFileSystem.TEMP_PREFIX + UUID.randomUUID())
        try {
            FileOutputStream(tmp).use {
                writer(it)
                it.fd.sync()
            }
            AtomicFiles.move(tmp, target)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return stat(path) ?: throw IOException("File vanished after write: $path")
    }

    override fun delete(path: String): Boolean {
        val f = resolve(path)
        val ok = f.delete()
        var dir = f.parentFile
        while (dir != null && dir != root && dir.isDirectory && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile
        }
        return ok
    }
}
