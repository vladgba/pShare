package x.vladgba.share.sync

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import x.vladgba.sync.core.model.FolderConfig
import x.vladgba.sync.core.model.SyncDirection
import x.vladgba.sync.core.net.FolderOffer

/** Dialog for creating or editing a synced folder and its rules. */
@TargetApi(Build.VERSION_CODES.O)
class FolderEditor(
    private val activity: Activity,
    private val p: Palette,
    private val app: SyncHub,
    private val config: FolderConfig,
    private val offer: FolderOffer?,
    private val isNew: Boolean = false,
) {
    fun show() {
        val ctx = activity
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(4), ctx.dp(24), ctx.dp(8))
        }
        body.text(SafFileSystem.describe(Uri.parse(config.location)), p.muted, 13f)
        val label = body.field(p, "Name", config.label)
        val id = body.field(p, "Folder ID (same on every device)", config.folderId, hint = "e.g. photos")
        if (!isNew || offer != null) {
            id.isEnabled = false
            id.alpha = 0.6f
        }

        body.text("Direction", p.muted, 13f) { setPadding(0, ctx.dp(14), 0, ctx.dp(2)) }
        val group = RadioGroup(ctx)
        val options = listOf(
            SyncDirection.TWO_WAY to "Two-way — changes go both ways",
            SyncDirection.SEND_ONLY to "Send only — publish, ignore peers' edits",
            SyncDirection.RECEIVE_ONLY to "Receive only — mirror peers, never send",
            SyncDirection.MOVE_OUT to "Move to peers — delete here once a peer has the file",
        )
        val buttons = options.map { (dir, text) ->
            RadioButton(ctx).apply {
                this.text = text
                textSize = 14f
                setTextColor(p.text)
                buttonTintList = ColorStateList.valueOf(p.accent)
                this.id = android.view.View.generateViewId()
                isChecked = dir == config.direction
            }.also { group.addView(it) }
        }
        body.addView(group)

        val include = body.field(
            p, "Only these files (one pattern per line, empty = everything)",
            config.include.joinToString("\n"), hint = "*.jpg\nDocuments/**/*.md", multiline = true,
        )
        val exclude = body.field(
            p, "Skip these files", config.exclude.joinToString("\n"), hint = "*.tmp\ncache/", multiline = true,
        )
        val maxSize = body.field(p, "Max file size, MB (empty = no limit)", config.maxFileSizeMb?.toString() ?: "", number = true)
        val keep = body.field(p, "Old versions to keep per file", config.keepVersions.toString(), number = true)

        val peers = app.peers.items.value
        val peerBoxes = if (peers.isNotEmpty()) {
            body.text("Share with (none ticked = all paired devices)", p.muted, 13f) { setPadding(0, ctx.dp(14), 0, 0) }
            peers.map { peer ->
                CheckBox(ctx).apply {
                    text = peer.name
                    setTextColor(p.text)
                    buttonTintList = ColorStateList.valueOf(p.accent)
                    isChecked = peer.deviceId in config.peers
                    tag = peer.deviceId
                }.also { body.addView(it) }
            }
        } else emptyList()

        val scroll = ScrollView(ctx).apply { addView(body) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (isNew) "Add folder" else "Edit “${config.label}”")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newId = id.text.toString().trim().lowercase()
                if (!FolderConfig.isValidId(newId)) {
                    id.error = "Use a–z, 0–9, '.', '-', '_' (max 64)"
                    return@setOnClickListener
                }
                if (isNew && app.folderConfigs.items.value.any { it.folderId == newId }) {
                    id.error = "You already have a folder with this ID"
                    return@setOnClickListener
                }
                val updated = config.copy(
                    folderId = newId,
                    label = label.text.toString().trim().ifEmpty { newId },
                    direction = options[buttons.indexOfFirst { it.isChecked }.coerceAtLeast(0)].first,
                    include = lines(include.text.toString()),
                    exclude = lines(exclude.text.toString()),
                    maxFileSizeMb = maxSize.text.toString().toLongOrNull()?.takeIf { it > 0 },
                    keepVersions = keep.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 5,
                    peers = peerBoxes.filter { it.isChecked }.map { it.tag as String }.toSet(),
                )
                app.folderConfigs.upsert(updated)
                app.node.launch { app.node.syncNow() }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
