package x.vladgba.share.sync

import x.vladgba.share.R

import android.Manifest
import android.annotation.TargetApi
import x.vladgba.share.pui.PAboutInfo
import x.vladgba.share.pui.showPAbout
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import x.vladgba.sync.core.crypto.DeviceIdentity
import x.vladgba.sync.core.model.FolderConfig
import x.vladgba.sync.core.model.SyncDirection
import x.vladgba.sync.core.net.FolderOffer
import x.vladgba.sync.core.sync.LogLevel
import x.vladgba.sync.core.sync.SyncNode
import x.vladgba.sync.core.sync.formatBytes
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@TargetApi(Build.VERSION_CODES.O)
class SyncActivity : Activity() {
    private enum class Tab(val title: String) { DEVICES("Devices"), FOLDERS("Folders"), LOG("Activity") }

    private val app get() = SyncHub.get(this)
    private val node get() = app.node
    private lateinit var p: Palette
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var subtitle: TextView
    private val tabViews = HashMap<Tab, TextView>()
    private var tab = Tab.DEVICES

    private val handler = Handler(Looper.getMainLooper())
    private val subscriptions = mutableListOf<Closeable>()
    private var renderPending = false

    private var pairingDialog: AlertDialog? = null
    private var pairingShown: PairingBridge.Pending? = null

    /** Remote folder the user is adding (from a peer's offer) while the folder picker is open. */
    private var offerBeingAdded: FolderOffer? = null

    private var serviceEnabled: Boolean
        get() = SyncHub.isEnabled(this)
        set(v) = SyncHub.setEnabled(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        tab = savedInstanceState?.getString("tab")?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.DEVICES
        offerBeingAdded = savedInstanceState?.getString("offerId")?.let {
            FolderOffer(it, savedInstanceState.getString("offerLabel") ?: it, sends = true, accepts = true)
        }
        setContentView(buildShell())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        if (serviceEnabled) SyncService.start(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
        offerBeingAdded?.let {
            outState.putString("offerId", it.folderId)
            outState.putString("offerLabel", it.label)
        }
    }

    override fun onStart() {
        super.onStart()
        val rerender: (Any?) -> Unit = { scheduleRender() }
        subscriptions += node.state.observe(rerender)
        subscriptions += node.discovery.peers.observe(rerender)
        subscriptions += node.remoteOffers.observe(rerender)
        subscriptions += app.peers.items.observe(rerender)
        subscriptions += app.folderConfigs.items.observe(rerender)
        subscriptions += app.folders.stats.observe(rerender)
        subscriptions += app.log.lines.observe { if (tab == Tab.LOG) scheduleRender() }
        subscriptions += app.pairing.pending.observe { pending -> handler.post { showPairing(pending) } }
        // Relative times ("2 min ago") need a refresh now and then.
        handler.postDelayed(ticker, 30_000)
    }

    override fun onStop() {
        subscriptions.forEach { it.close() }
        subscriptions.clear()
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private val ticker = object : Runnable {
        override fun run() {
            scheduleRender()
            handler.postDelayed(this, 30_000)
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildShell(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), 0)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(20), dp(4))
        }
        top.text("Folder sync", p.text, 24f, bold = true)
        subtitle = top.text("", p.muted, 13f)
        header.addView(android.widget.ImageButton(this, null, 0, R.style.P_IconButton).apply {
            setImageResource(R.drawable.ic_arrow_back)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        header.addView(top, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(android.widget.ImageButton(this, null, 0, R.style.P_IconButton).apply {
            setImageResource(R.drawable.p_ic_info)
            contentDescription = "About"
            setOnClickListener { showPAbout(PAboutInfo(repo = "pShare", license = false)) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
        }
        for (t in Tab.entries) {
            val v = TextView(this).apply {
                text = t.title
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    tab = t
                    scroll.scrollTo(0, 0)
                    render()
                }
            }
            tabs.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
            tabViews[t] = v
        }
        root.addView(tabs)

        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Edge-to-edge (enforced on Android 15): pad for the system bars ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            v.setPadding(l, t, r, 0)
            scroll.setPadding(0, 0, 0, b)
            insets
        }
        return root
    }

    private fun scheduleRender() {
        handler.post {
            if (renderPending) return@post
            renderPending = true
            handler.postDelayed({
                renderPending = false
                render()
            }, 120)
        }
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val st = node.state.value
        subtitle.text = "${node.identity.name} · " + when {
            st.syncing.isNotEmpty() -> "syncing…"
            st.running -> "ready"
            else -> "stopped"
        }
        for ((t, v) in tabViews) {
            val selected = t == tab
            v.setTextColor(if (selected) p.onAccentSoft else p.muted)
            v.background = if (selected) rounded(p.accentSoft, dp(18).toFloat()) else null
        }
        val y = scroll.scrollY
        content.removeAllViews()
        when (tab) {
            Tab.DEVICES -> renderDevices()
            Tab.FOLDERS -> renderFolders()
            Tab.LOG -> renderLog()
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ------------------------------------------------------------------ devices

    private fun renderDevices() {
        val st = node.state.value
        val online = node.discovery.peers.value
        val paired = app.peers.items.value

        content.card(p) {
            text("This device", p.muted, 12f)
            text(node.identity.name, p.text, 19f, bold = true) { setOnClickListener { renameDialog() } }
            text("ID " + DeviceIdentity.format(node.deviceId), p.muted, 12f, mono = true)
            space(6)
            horizontal {
                text(if (st.running) "● Running · port ${st.port}" else "○ Stopped", if (st.running) p.ok else p.muted, 13f)
            }
            space(4)
            horizontal {
                if (st.running) {
                    button(p, "Sync now", primary = true) { SyncService.syncNow(this@SyncActivity) }
                    button(p, "Stop") {
                        serviceEnabled = false
                        SyncService.stop(this@SyncActivity)
                    }
                } else {
                    button(p, "Start", primary = true) {
                        serviceEnabled = true
                        SyncService.start(this@SyncActivity)
                    }
                }
                button(p, "Rename") { renameDialog() }
            }
        }

        content.header(p, "Paired devices")
        if (paired.isEmpty()) {
            content.text(
                "No paired devices yet. Open Folder sync in pShare (or pSync) on another device on the same Wi-Fi, then pair it below.",
                p.muted, 14f,
            ) { setPadding(dp(4), dp(6), dp(4), 0) }
        }
        for (peer in paired.sortedBy { it.name.lowercase() }) {
            val here = online[peer.deviceId]
            content.card(p) {
                horizontal {
                    text(peer.name, p.text, 16f, bold = true)
                    when {
                        peer.deviceId in st.syncing -> chip(p, "syncing", p.accentText)
                        here != null -> chip(p, "online", p.ok)
                        else -> chip(p, "offline", p.muted)
                    }
                }
                val where = here?.let { "${it.platform} · ${it.host}" } ?: peer.lastHost?.let { "last at $it" } ?: ""
                text(listOf(where, "synced ${relativeTime(peer.lastSync)}").filter { it.isNotEmpty() }.joinToString(" · "), p.muted, 13f)
                horizontal {
                    button(p, "Sync") { node.launch { node.syncPeer(peer.deviceId) } }
                    button(p, "Unpair") {
                        confirm("Unpair ${peer.name}?", "It will need to be paired again to sync. Files stay where they are.") {
                            app.peers.remove(peer.deviceId)
                        }
                    }
                }
            }
        }

        content.header(p, "Nearby")
        val unpaired = online.values.filter { app.peers.get(it.deviceId) == null }.sortedBy { it.name.lowercase() }
        if (unpaired.isEmpty()) {
            content.text(
                if (st.running) "Looking for devices on this network…" else "Start the service to find devices.",
                p.muted, 14f,
            ) { setPadding(dp(4), dp(6), dp(4), 0) }
        }
        for (d in unpaired) {
            content.card(p) {
                text(d.name, p.text, 16f, bold = true)
                text("${d.platform} · ${d.host}", p.muted, 13f)
                horizontal {
                    button(p, "Pair", primary = true) { node.launch { node.connect(d.host, d.port, allowPairing = true) } }
                }
            }
        }
        content.space(6)
        content.horizontal { button(p, "Connect by address…") { connectDialog() } }
    }

    // ------------------------------------------------------------------ folders

    private fun renderFolders() {
        val configs = app.folderConfigs.items.value
        val stats = app.folders.stats.value
        val known = configs.map { it.folderId }.toSet()

        content.horizontal { button(p, "Add folder", primary = true) { pickFolder(null) } }

        val offers = node.remoteOffers.value.flatMap { (peerId, list) ->
            val name = app.peers.get(peerId)?.name ?: "A device"
            list.filter { it.folderId !in known }.map { name to it }
        }.distinctBy { it.second.folderId }
        if (offers.isNotEmpty()) {
            content.header(p, "Shared with you")
            for ((peerName, offer) in offers) {
                content.card(p) {
                    text("$peerName shares “${offer.label}”", p.text, 15f, bold = true)
                    text("Folder ID: ${offer.folderId}", p.muted, 13f, mono = true)
                    horizontal { button(p, "Choose where to keep it…") { pickFolder(offer) } }
                }
            }
        }

        content.header(p, "Synced folders")
        if (configs.isEmpty()) {
            content.text(
                "Add a folder and give it a Folder ID. Devices sync folders that have the same ID.",
                p.muted, 14f,
            ) { setPadding(dp(4), dp(6), dp(4), 0) }
        }
        for (c in configs) {
            val s = stats[c.folderId]
            content.card(p, onClick = { FolderEditor(this, p, app, c, offer = null).show() }) {
                horizontal {
                    text(c.label, p.text, 16f, bold = true)
                    chip(p, directionLabel(c.direction), p.accentText)
                    if (c.paused) chip(p, "paused", p.warn)
                }
                text(SafFileSystem.describe(Uri.parse(c.location)), p.muted, 13f)
                text("ID ${c.folderId}", p.muted, 12f, mono = true)
                if (s != null) {
                    text("${s.files} files · ${formatBytes(s.bytes)} · synced ${relativeTime(s.lastSync)}" +
                        if (s.conflicts > 0) " · ${s.conflicts} conflict copies" else "", p.muted, 13f)
                    s.error?.let { text("⚠ $it", p.error, 13f) }
                }
                horizontal {
                    button(p, "Scan") { node.launch { node.syncNow() } }
                    button(p, if (c.paused) "Resume" else "Pause") { app.folderConfigs.upsert(c.copy(paused = !c.paused)) }
                    button(p, "Remove") { removeFolder(c) }
                }
            }
        }
    }

    private fun removeFolder(c: FolderConfig) = confirm(
        "Stop syncing “${c.label}”?",
        "Files in the folder are not deleted. Sync history for it is forgotten.",
    ) {
        app.folderConfigs.remove(c.folderId)
        app.folders.forget(c.folderId)
        val uri = Uri.parse(c.location)
        if (app.folderConfigs.items.value.none { it.location == c.location }) {
            runCatching {
                contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private fun pickFolder(offer: FolderOffer?) {
        offerBeingAdded = offer
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_TREE)
    }

    @Deprecated("Framework API; fine without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val offer = offerBeingAdded
        offerBeingAdded = null
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val name = SafFileSystem.describe(uri).substringAfterLast('/')
        val existing = app.folderConfigs.items.value.map { it.folderId }.toSet()
        var id = offer?.folderId ?: FolderConfig.slug(name)
        if (offer == null) {
            var n = 2
            val base = id
            while (id in existing) id = "$base-${n++}"
        }
        val draft = FolderConfig(
            folderId = id,
            label = offer?.label ?: name,
            location = uri.toString(),
            direction = when {
                offer == null -> SyncDirection.TWO_WAY
                offer.sends && offer.accepts -> SyncDirection.TWO_WAY
                offer.sends -> SyncDirection.RECEIVE_ONLY
                else -> SyncDirection.SEND_ONLY
            },
        )
        FolderEditor(this, p, app, draft, offer, isNew = true).show()
    }

    // ------------------------------------------------------------------ log

    private fun renderLog() {
        val lines = app.log.lines.value
        if (lines.isEmpty()) {
            content.text("Nothing has happened yet.", p.muted, 14f) { setPadding(dp(4), dp(12), 0, 0) }
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        content.space(6)
        for (l in lines.asReversed()) {
            content.horizontal {
                gravity = Gravity.TOP
                text(fmt.format(Date(l.time)), p.muted, 12f, mono = true) {
                    layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                text(
                    l.text,
                    when (l.level) {
                        LogLevel.INFO -> p.text
                        LogLevel.WARN -> p.warn
                        LogLevel.ERROR -> p.error
                    },
                    13f,
                )
            }.setPadding(0, dp(5), 0, dp(5))
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun showPairing(pending: PairingBridge.Pending?) {
        if (pending === pairingShown) return
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingShown = pending
        if (pending == null || isFinishing) return
        val r = pending.request
        var answered = false
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        body.text(
            if (r.initiatedByUs) "Check that ${r.name} shows the same code, then confirm on both devices."
            else "${r.name} (${r.platform}) wants to pair. Confirm only if it shows the same code.",
            p.text, 15f,
        )
        body.text(r.code.chunked(3).joinToString(" "), p.accentText, 34f, bold = true, mono = true) {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        pairingDialog = AlertDialog.Builder(this)
            .setTitle("Pair with ${r.name}?")
            .setView(body)
            .setPositiveButton("Pair") { _, _ ->
                answered = true
                app.pairing.answer(pending, true)
            }
            .setNegativeButton("Cancel") { _, _ ->
                answered = true
                app.pairing.answer(pending, false)
            }
            .setOnDismissListener {
                if (!answered && pairingShown === pending && app.pairing.pending.value === pending) app.pairing.answer(pending, false)
            }
            .show()
    }

    private fun renameDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val name = body.field(p, "Device name", node.identity.name)
        AlertDialog.Builder(this)
            .setTitle("Rename this device")
            .setView(body)
            .setPositiveButton("Save") { _, _ ->
                app.renameDevice(name.text.toString())
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        body.text("Use this when discovery is blocked (guest Wi-Fi, firewall, VPN).", p.muted, 13f) {
            setPadding(0, dp(4), 0, 0)
        }
        val host = body.field(p, "IP address", hint = "192.168.1.20")
        val port = body.field(p, "Port", SyncNode.DEFAULT_SERVICE_PORT.toString(), number = true)
        val pair = CheckBox(this).apply { text = "Pair if it's a new device"; isChecked = true }
        body.addView(pair)
        AlertDialog.Builder(this)
            .setTitle("Connect by address")
            .setView(body)
            .setPositiveButton("Connect") { _, _ ->
                val h = host.text.toString().trim()
                val pt = port.text.toString().toIntOrNull() ?: SyncNode.DEFAULT_SERVICE_PORT
                if (h.isNotEmpty()) node.launch { node.connect(h, pt, allowPairing = pair.isChecked) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REQ_TREE = 10
        private const val REQ_NOTIFICATIONS = 11

        fun directionLabel(d: SyncDirection) = when (d) {
            SyncDirection.TWO_WAY -> "two-way"
            SyncDirection.SEND_ONLY -> "send only"
            SyncDirection.RECEIVE_ONLY -> "receive only"
            SyncDirection.MOVE_OUT -> "move to peers"
        }
    }
}
