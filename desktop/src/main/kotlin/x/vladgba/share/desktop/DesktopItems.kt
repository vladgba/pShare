package x.vladgba.share.desktop

import x.vladgba.share.core.DirItem
import x.vladgba.share.core.ItemInfo
import x.vladgba.share.core.OutgoingItem
import x.vladgba.share.core.ReceiveSink
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopFileItem(
    val file: File,
    relativePath: String
) : OutgoingItem {
    override val info: ItemInfo = ItemInfo(
        kind = ItemInfo.KIND_FILE,
        name = relativePath.replace('\\', '/'),
        mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream",
        size = file.length()
    )

    override fun open(): InputStream = FileInputStream(file)
}

object DesktopItemResolver {
    fun resolve(files: List<File>): List<OutgoingItem> {
        val result = mutableListOf<OutgoingItem>()
        for (f in files) {
            if (f.isDirectory) {
                result.add(DirItem(f.name))
                walkDirectory(f, f.name, result)
            } else if (f.isFile) {
                result.add(DesktopFileItem(f, f.name))
            }
        }
        return result
    }

    private fun walkDirectory(dir: File, prefix: String, list: MutableList<OutgoingItem>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val rel = "$prefix/${child.name}"
            if (child.isDirectory) {
                list.add(DirItem(rel))
                walkDirectory(child, rel, list)
            } else if (child.isFile) {
                list.add(DesktopFileItem(child, rel))
            }
        }
    }
}

class DesktopSink(
    val destinationDir: File = File(System.getProperty("user.home"), "Downloads/pShare"),
    private val onItemStart: (Int, ItemInfo) -> Unit = { _, _ -> },
    private val onItemFinish: (Int, ItemInfo, Boolean) -> Unit = { _, _, _ -> },
    private val onAllComplete: () -> Unit = {}
) : ReceiveSink {
    private val tempFiles = HashMap<Int, File>()
    private val targetFiles = HashMap<Int, File>()

    init {
        destinationDir.mkdirs()
    }

    override fun begin(items: List<ItemInfo>) {
        // Pre-create any directory entries
        for (item in items) {
            if (item.isDir) {
                File(destinationDir, item.name.replace('/', File.separatorChar)).mkdirs()
            }
        }
    }

    override fun open(index: Int, info: ItemInfo): OutputStream {
        onItemStart(index, info)
        if (info.isDir) {
            File(destinationDir, info.name.replace('/', File.separatorChar)).mkdirs()
            return OutputStream.nullOutputStream()
        }
        val target = File(destinationDir, info.name.replace('/', File.separatorChar))
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.part-${System.currentTimeMillis()}")
        tempFiles[index] = temp
        targetFiles[index] = target
        return FileOutputStream(temp)
    }

    override fun finish(index: Int, info: ItemInfo, ok: Boolean) {
        val temp = tempFiles.remove(index)
        val target = targetFiles.remove(index)
        if (temp != null && target != null) {
            if (ok && temp.exists()) {
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } else {
                temp.delete()
            }
        }
        onItemFinish(index, info, ok)
    }

    override fun complete() {
        onAllComplete()
    }
}
