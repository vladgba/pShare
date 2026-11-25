package x.vladgba.sync.core.model

import x.vladgba.sync.core.util.JObj

enum class Ordering { EQUAL, BEFORE, AFTER, CONCURRENT }

/**
 * Version vector: per-device counters of edits. Two versions are comparable when one
 * vector dominates the other; otherwise the edits were concurrent (a real conflict).
 */
data class VersionVector(val c: Map<String, Long> = emptyMap()) {

    fun increment(deviceId: String): VersionVector =
        VersionVector(c + (deviceId to (c[deviceId] ?: 0L) + 1))

    fun merge(other: VersionVector): VersionVector {
        val keys = c.keys + other.c.keys
        return VersionVector(keys.associateWith { maxOf(c[it] ?: 0L, other.c[it] ?: 0L) })
    }

    /** How `this` relates to [other]: BEFORE means [other] has seen everything we have and more. */
    fun compare(other: VersionVector): Ordering {
        var greater = false
        var less = false
        for (k in c.keys + other.c.keys) {
            val a = c[k] ?: 0L
            val b = other.c[k] ?: 0L
            if (a > b) greater = true
            if (a < b) less = true
            if (greater && less) return Ordering.CONCURRENT
        }
        return when {
            greater -> Ordering.AFTER
            less -> Ordering.BEFORE
            else -> Ordering.EQUAL
        }
    }

    fun toJson(): Map<String, Any?> = c

    override fun toString(): String = c.entries.joinToString(",", "{", "}") { "${it.key.take(4)}:${it.value}" }

    companion object {
        fun fromJson(o: JObj?): VersionVector =
            VersionVector(o?.map?.mapValues { (it.value as Number).toLong() } ?: emptyMap())
    }
}

/**
 * One file (or tombstone) in a folder index.
 *
 * [size] and [modifiedAt] describe the copy on the device that owns the index and are only
 * used locally for cheap change detection. [hash] + [version] are what devices compare.
 */
data class FileEntry(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
    /** SHA-256 hex of the content; empty for tombstones. */
    val hash: String,
    val deleted: Boolean = false,
    val version: VersionVector = VersionVector(),
    /** Previous content hashes, oldest first. Used to find the common ancestor for merges. */
    val history: List<String> = emptyList(),
    val changedBy: String = "",
    val changedAt: Long = 0,
) {
    fun toJson(): Map<String, Any?> = buildMap {
        put("p", path)
        put("s", size)
        put("m", modifiedAt)
        put("h", hash)
        if (deleted) put("d", true)
        put("v", version.toJson())
        if (history.isNotEmpty()) put("hi", history)
        put("cb", changedBy)
        put("ca", changedAt)
    }

    companion object {
        fun fromJson(o: JObj) = FileEntry(
            path = o.str("p"),
            size = o.long("s"),
            modifiedAt = o.long("m"),
            hash = o.str("h"),
            deleted = o.bool("d"),
            version = VersionVector.fromJson(o.objOrNull("v")),
            history = o.strList("hi"),
            changedBy = o.strOr("cb", "")!!,
            changedAt = o.longOr("ca", 0)!!,
        )
    }
}

const val HISTORY_DEPTH = 16

/** History list for the next version of this entry. */
fun FileEntry?.nextHistory(): List<String> = when {
    this == null -> emptyList()
    deleted || hash.isEmpty() -> history
    else -> (history + hash).takeLast(HISTORY_DEPTH)
}

enum class SyncDirection {
    /** Send local changes and accept remote ones. */
    TWO_WAY,
    /** Only publish this folder; ignore changes made by peers. */
    SEND_ONLY,
    /** Only accept changes from peers; never publish local edits. */
    RECEIVE_ONLY,
    /** Publish files and delete them locally once a peer confirms it stored them. */
    MOVE_OUT;

    val sends: Boolean get() = this != RECEIVE_ONLY
    val accepts: Boolean get() = this == TWO_WAY || this == RECEIVE_ONLY
}

/** A synced folder as configured on one device. Peers pair folders by [folderId]. */
data class FolderConfig(
    val folderId: String,
    val label: String,
    /** Platform-specific location: a SAF tree URI on Android, an absolute path on Windows. */
    val location: String,
    val direction: SyncDirection = SyncDirection.TWO_WAY,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val maxFileSizeMb: Long? = null,
    /** Device IDs allowed to sync this folder; empty = all paired devices. */
    val peers: Set<String> = emptySet(),
    val keepVersions: Int = 5,
    val paused: Boolean = false,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "folderId" to folderId,
        "label" to label,
        "location" to location,
        "direction" to direction.name,
        "include" to include,
        "exclude" to exclude,
        "maxFileSizeMb" to maxFileSizeMb,
        "peers" to peers.toList(),
        "keepVersions" to keepVersions,
        "paused" to paused,
    )

    companion object {
        private val ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        fun isValidId(id: String) = ID_REGEX.matches(id)
        fun slug(label: String): String =
            label.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.', '_').take(64).ifEmpty { "folder" }

        fun fromJson(o: JObj) = FolderConfig(
            folderId = o.str("folderId"),
            label = o.str("label"),
            location = o.str("location"),
            direction = runCatching { SyncDirection.valueOf(o.str("direction")) }.getOrDefault(SyncDirection.TWO_WAY),
            include = o.strList("include"),
            exclude = o.strList("exclude"),
            maxFileSizeMb = o.longOr("maxFileSizeMb", null),
            peers = o.strList("peers").toSet(),
            keepVersions = o.longOr("keepVersions", 5)!!.toInt(),
            paused = o.bool("paused"),
        )
    }
}

data class PeerInfo(
    val deviceId: String,
    val name: String,
    /** Base64 X.509 identity public key, pinned at pairing time. */
    val publicKey: String,
    val pairedAt: Long,
    val lastHost: String? = null,
    val lastPort: Int? = null,
    val lastSeen: Long = 0,
    val lastSync: Long = 0,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "name" to name,
        "publicKey" to publicKey,
        "pairedAt" to pairedAt,
        "lastHost" to lastHost,
        "lastPort" to lastPort,
        "lastSeen" to lastSeen,
        "lastSync" to lastSync,
    )

    companion object {
        fun fromJson(o: JObj) = PeerInfo(
            deviceId = o.str("deviceId"),
            name = o.str("name"),
            publicKey = o.str("publicKey"),
            pairedAt = o.longOr("pairedAt", 0)!!,
            lastHost = o.strOr("lastHost", null),
            lastPort = o.longOr("lastPort", null)?.toInt(),
            lastSeen = o.longOr("lastSeen", 0)!!,
            lastSync = o.longOr("lastSync", 0)!!,
        )
    }
}
