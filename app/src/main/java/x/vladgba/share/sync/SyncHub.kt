package x.vladgba.share.sync

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import x.vladgba.sync.core.crypto.IdentityStore
import x.vladgba.sync.core.sync.EventLog
import x.vladgba.sync.core.sync.FolderConfigStore
import x.vladgba.sync.core.sync.FolderRegistry
import x.vladgba.sync.core.sync.LogLevel
import x.vladgba.sync.core.sync.PairingHandler
import x.vladgba.sync.core.sync.PairingRequest
import x.vladgba.sync.core.sync.PeerStore
import x.vladgba.sync.core.sync.SyncNode
import x.vladgba.sync.core.util.Observable
import x.vladgba.share.R
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Process-wide folder sync graph (the pSync engine inside pShare). Created lazily on first use,
 * so plain sending and receiving never pays for it. Sync needs Android 8.0+ (java.time, Base64).
 */
@TargetApi(Build.VERSION_CODES.O)
class SyncHub private constructor(private val context: Context) {
    val log: EventLog = EventLog(sink = { line ->
        when (line.level) {
            LogLevel.INFO -> Log.i(TAG, line.text)
            LogLevel.WARN -> Log.w(TAG, line.text)
            LogLevel.ERROR -> Log.e(TAG, line.text)
        }
    })
    private val dir = File(context.filesDir, "sync").apply { mkdirs() }
    private val identityFile get() = File(dir, "identity.json")
    private val identity = IdentityStore.loadOrCreate(identityFile, defaultDeviceName())
    val peers = PeerStore(File(dir, "peers.json"))
    val folderConfigs = FolderConfigStore(File(dir, "folders.json"))
    val folders = FolderRegistry(dir, identity.deviceId) {
        SafFileSystem(context, Uri.parse(it.location))
    }
    val pairing = PairingBridge(context)
    val node: SyncNode

    init {
        SyncNotifications.createChannels(context)
        folderConfigs.items.observe { folders.apply(it) }
        node = SyncNode(identity, "android", peers, folders, pairing, log)
    }

    fun renameDevice(name: String) {
        val updated = node.identity.withName(name.trim().ifEmpty { defaultDeviceName() })
        IdentityStore.save(identityFile, updated)
        node.identity = updated
    }

    private fun defaultDeviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        const val TAG = "pShare.sync"

        /** Folder sync is available from Android 8.0 (API 26); older phones only get transfers. */
        @get:SuppressLint("ObsoleteSdkInt") // the class is API 26+, but this check guards older phones
        val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        private const val PREFS = "sync_ui"
        private const val KEY_ENABLED = "service"

        /** Whether the user turned folder sync on; off until they press Start the first time. */
        fun isEnabled(context: Context): Boolean =
            supported && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()

        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: SyncHub? = null

        fun get(context: Context): SyncHub = instance ?: synchronized(this) {
            instance ?: SyncHub(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Bridges the core's blocking pairing callback to the UI: publishes the request, shows a
 * notification if the app isn't open, and waits for the user's answer.
 */
@TargetApi(Build.VERSION_CODES.O)
class PairingBridge(private val context: Context) : PairingHandler {
    class Pending(val request: PairingRequest, val answer: CompletableFuture<Boolean>)

    val pending = Observable<Pending?>(null)

    override fun confirm(request: PairingRequest): Boolean {
        val p = Pending(request, CompletableFuture())
        pending.set(p)
        if (!request.initiatedByUs) SyncNotifications.showPairing(context, request)
        return try {
            p.answer.get(SyncNode.PAIRING_TIMEOUT_MS - 5_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        } finally {
            pending.update { if (it === p) null else it }
            SyncNotifications.cancelPairing(context)
        }
    }

    fun answer(p: Pending, accept: Boolean) {
        p.answer.complete(accept)
    }
}

@TargetApi(Build.VERSION_CODES.O)
object SyncNotifications {
    const val CHANNEL_SERVICE = "sync_service"
    const val CHANNEL_PAIRING = "sync_pairing"
    const val ID_SERVICE = 0x5301
    private const val ID_PAIRING = 0x5302

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Folder sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while folder sync is running"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PAIRING, "Pairing requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A device wants to pair with this one"
            }
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, SyncActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showPairing(context: Context, request: PairingRequest) {
        val n = Notification.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("Pair with ${request.name}?")
            .setContentText("Open pShare sync and check the code: ${request.code}")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID_PAIRING, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelPairing(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(ID_PAIRING)
}
