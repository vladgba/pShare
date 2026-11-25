package x.vladgba.share.sync

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import x.vladgba.share.R
import java.io.Closeable

/**
 * Foreground service that keeps the sync node running: listens for peers, broadcasts
 * discovery beacons, rescans folders periodically and syncs.
 */
@TargetApi(Build.VERSION_CODES.O)
class SyncService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var stateWatch: Closeable? = null

    private val app get() = SyncHub.get(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground("Starting…")
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // Without this many devices drop incoming broadcast packets (discovery beacons).
        multicastLock = wifi.createMulticastLock("pShare:syncDiscovery").apply {
            setReferenceCounted(false); acquire()
        }
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pShare:sync").apply {
            setReferenceCounted(false); acquire()
        }
        app.node.start()
        stateWatch = app.node.state.observe { s ->
            val text = when {
                s.syncing.isNotEmpty() -> "Syncing with ${s.syncing.size} device(s)…"
                s.running -> "Ready · port ${s.port}"
                else -> "Stopped"
            }
            updateNotification(text)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> app.node.launch { app.node.syncNow() }
        }
        return START_STICKY
    }

    /** Android 15+: dataSync services get ~6h per day; stop gracefully when the budget runs out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.log.warn("Android stopped background sync (daily limit reached); open the app to resume")
        stopSelf()
    }

    override fun onDestroy() {
        stateWatch?.close()
        app.node.stop()
        multicastLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val sync = PendingIntent.getService(
            this, 2, Intent(this, SyncService::class.java).setAction(ACTION_SYNC_NOW), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, SyncNotifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("pShare folder sync")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(SyncNotifications.openAppIntent(this))
            .addAction(Notification.Action.Builder(null, "Sync now", sync).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SyncNotifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SyncNotifications.ID_SERVICE, n)
        }
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(SyncNotifications.ID_SERVICE, buildNotification(text))
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_STOP = "x.vladgba.share.sync.STOP"
        const val ACTION_SYNC_NOW = "x.vladgba.share.sync.SYNC_NOW"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun syncNow(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java).setAction(ACTION_SYNC_NOW))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
