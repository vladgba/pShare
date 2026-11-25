package x.vladgba.sync.core.rules

import x.vladgba.sync.core.fs.SyncFileSystem
import x.vladgba.sync.core.model.FolderConfig

/**
 * gitignore-flavoured glob:
 *  - `*.tmp`      no slash → matched against the file name
 *  - `docs/x.md`   with slash → matched against the whole relative path
 *  - `build/`     trailing slash → everything under any directory named `build`
 *  - `/build/`    leading slash → anchored to the folder root
 *  - `**` any number of directories, `*` anything but '/', `?` one char
 */
class Glob(val pattern: String, ignoreCase: Boolean = true) {
    private val regex: Regex
    private val matchName: Boolean

    init {
        var p = pattern.trim().replace('\\', '/')
        val dirOnly = p.endsWith("/")
        if (dirOnly) p = p.trimEnd('/')
        val anchored = p.startsWith("/")
        if (anchored) p = p.trimStart('/')
        matchName = !dirOnly && !anchored && !p.contains('/')
        val body = toRegex(p)
        val full = if (dirOnly) (if (anchored) "" else "(?:.*/)?") + body + "/.*" else body
        regex = if (ignoreCase) Regex(full, RegexOption.IGNORE_CASE) else Regex(full)
    }

    fun matches(path: String): Boolean =
        if (matchName) regex.matches(path.substringAfterLast('/')) else regex.matches(path)

    private fun toRegex(p: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '*' && i + 1 < p.length && p[i + 1] == '*' -> {
                    if (i + 2 < p.length && p[i + 2] == '/') {
                        sb.append("(?:.*/)?"); i += 3; continue
                    }
                    sb.append(".*"); i += 2; continue
                }
                ch == '*' -> sb.append("[^/]*")
                ch == '?' -> sb.append("[^/]")
                ch in "\\.[]{}()+-^$|" -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}

class PathFilter(
    include: List<String> = emptyList(),
    exclude: List<String> = emptyList(),
    private val maxSize: Long? = null,
) {
    private val includes = include.filter { it.isNotBlank() }.map { Glob(it) }
    private val excludes = (DEFAULT_EXCLUDES + exclude).filter { it.isNotBlank() }.map { Glob(it) }

    fun accepts(path: String, size: Long = 0): Boolean {
        if (maxSize != null && size > maxSize) return false
        if (excludes.any { it.matches(path) }) return false
        return includes.isEmpty() || includes.any { it.matches(path) }
    }

    companion object {
        val DEFAULT_EXCLUDES = listOf(
            "${SyncFileSystem.TEMP_PREFIX}*",
            ".localbridge/",
            ".thumbnails/",
            ".trashed-*",
            ".pending-*",
            "Thumbs.db",
            "desktop.ini",
            ".DS_Store",
            "~$*",
        )

        fun from(config: FolderConfig) = PathFilter(
            include = config.include,
            exclude = config.exclude,
            maxSize = config.maxFileSizeMb?.let { it * 1024 * 1024 },
        )
    }
}
