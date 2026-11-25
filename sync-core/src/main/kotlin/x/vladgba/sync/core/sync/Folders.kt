package x.vladgba.sync.core.sync

import x.vladgba.sync.core.fs.SyncFileSystem
import x.vladgba.sync.core.index.BlobStore
import x.vladgba.sync.core.index.FolderIndex
import x.vladgba.sync.core.index.JsonIndexStore
import x.vladgba.sync.core.index.ScanResult
import x.vladgba.sync.core.index.Scanner
import x.vladgba.sync.core.index.VersionArchive
import x.vladgba.sync.core.model.FolderConfig
import x.vladgba.sync.core.rules.PathFilter
import x.vladgba.sync.core.util.Observable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class FolderStats(
    val files: Int = 0,
    val bytes: Long = 0,
    val lastScan: Long = 0,
    val lastSync: Long = 0,
    val conflicts: Int = 0,
    val error: String? = null,
)

/** Everything the engine needs for one configured folder. */
class FolderRuntime(
    config: FolderConfig,
    val fs: SyncFileSystem,
    dataDir: File,
    private val selfId: String,
    private val onStats: (String, FolderStats) -> Unit = { _, _ -> },
) {
    @Volatile
    var config: FolderConfig = config
        internal set

    val id: String get() = config.folderId
    val dir = File(dataDir, "folders/${config.folderId}")
    private val indexStore = JsonIndexStore(File(dir, "index.json"))
    val blobs = BlobStore(File(dir, "blobs"))
    val archive = VersionArchive(File(dir, "versions"))
    val staging = File(dir, "staging").apply { mkdirs() }

    /** Guards scanning and applying remote changes. Serving reads doesn't need it. */
    val lock = ReentrantLock()

    val index: FolderIndex by lazy { indexStore.load(config.folderId) }
    val filter: PathFilter get() = PathFilter.from(config)

    var stats = FolderStats()
        private set

    private var scansSinceGc = 0

    fun scan(): ScanResult = lock.withLock {
        try {
            staging.listFiles()?.forEach { it.delete() }
            val r = Scanner(selfId).scan(fs, index, filter, blobs)
            if (r.changed > 0) save()
            if (++scansSinceGc >= 20) {
                scansSinceGc = 0
                gcBlobs()
            }
            updateStats { it.copy(files = r.files, bytes = r.bytes, lastScan = System.currentTimeMillis(), error = null) }
            r
        } catch (e: Exception) {
            updateStats { it.copy(error = e.message ?: e.toString()) }
            throw e
        }
    }

    fun save() = indexStore.save(index)

    fun gcBlobs() {
        val keep = HashSet<String>()
        for (e in index.entries.values) {
            if (e.hash.isNotEmpty()) keep += e.hash
            keep += e.history
        }
        blobs.retainOnly(keep)
    }

    @Synchronized
    internal fun updateStats(f: (FolderStats) -> FolderStats) {
        stats = f(stats)
        onStats(id, stats)
    }

    /** Wipes local sync state. Used when the folder location changes or the folder is removed. */
    fun resetState() {
        indexStore.delete()
        dir.deleteRecursively()
    }
}

class FolderRegistry(
    private val dataDir: File,
    private val selfId: String,
    private val fsFactory: (FolderConfig) -> SyncFileSystem,
) {
    private val runtimes = LinkedHashMap<String, FolderRuntime>()
    val stats = Observable<Map<String, FolderStats>>(emptyMap())

    @Synchronized
    fun apply(configs: List<FolderConfig>) {
        val ids = configs.map { it.folderId }.toSet()
        runtimes.keys.filter { it !in ids }.forEach { runtimes.remove(it) }
        for (c in configs) {
            val existing = runtimes[c.folderId]
            if (existing != null && existing.config.location == c.location) {
                existing.config = c
                continue
            }
            if (existing != null) existing.resetState() // moved to another location: start fresh, never tombstone
            runtimes[c.folderId] = FolderRuntime(c, fsFactory(c), dataDir, selfId) { id, s ->
                stats.update { it + (id to s) }
            }
        }
        stats.update { m -> m.filterKeys { it in ids } }
    }

    /** Removes local sync state for a folder that is no longer configured. Files themselves stay. */
    fun forget(folderId: String) {
        File(dataDir, "folders/$folderId").deleteRecursively()
    }

    @Synchronized
    fun get(id: String): FolderRuntime? = runtimes[id]

    @Synchronized
    fun all(): List<FolderRuntime> = runtimes.values.toList()
}
