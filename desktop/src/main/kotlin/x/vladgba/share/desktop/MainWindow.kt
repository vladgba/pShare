package x.vladgba.share.desktop

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatDarkLaf
import x.vladgba.share.core.OutgoingItem
import x.vladgba.share.core.Pairing
import x.vladgba.share.core.Protocol
import x.vladgba.share.core.SecureChannel
import x.vladgba.share.core.TextItem
import x.vladgba.share.core.TransferListener
import x.vladgba.share.core.knownTotal
import x.vladgba.share.transport.Lan
import x.vladgba.share.transport.LanClient
import x.vladgba.share.transport.LanServer
import x.vladgba.share.transport.Link
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

class MainWindow : JFrame("pShare - Windows PC") {

    private val cardLayout = CardLayout()
    private val mainContainer = JPanel(cardLayout)

    private val homePanel = JPanel(GridBagLayout())
    private val sendPanel = JPanel(BorderLayout(16, 16))
    private val receivePanel = JPanel(BorderLayout(16, 16))
    private val transferPanel = JPanel(BorderLayout(16, 16))

    // Camera component
    private var camera: DesktopCamera? = null
    private val cameraPreview = CameraPreviewPanel()

    // State
    private val myName: String = try {
        InetAddress.getLocalHost().hostName
    } catch (_: Exception) {
        "Windows PC"
    }
    private var sendItems = mutableListOf<OutgoingItem>()
    private var lanServer: LanServer? = null
    private var lanClient: LanClient? = null
    private val transferClaimed = AtomicBoolean(false)

    // Transfer UI components
    private val lblTransferTitle = JLabel("Transferring...", SwingConstants.CENTER)
    private val lblTransferDetail = JLabel("Connecting...", SwingConstants.CENTER)
    private val progressBar = JProgressBar(0, 100)
    private val lblProgressStats = JLabel("0 MB / 0 MB", SwingConstants.CENTER)
    private val btnOpenFolder = JButton("Open Received Folder")

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(780, 640)
        minimumSize = Dimension(650, 500)
        setLocationRelativeTo(null)

        setupHomePanel()
        setupSendPanel()
        setupReceivePanel()
        setupTransferPanel()

        mainContainer.add(homePanel, "HOME")
        mainContainer.add(sendPanel, "SEND")
        mainContainer.add(receivePanel, "RECEIVE")
        mainContainer.add(transferPanel, "TRANSFER")

        contentPane.add(mainContainer)
        cardLayout.show(mainContainer, "HOME")
    }

    private fun setupHomePanel() {
        homePanel.border = BorderFactory.createEmptyBorder(32, 32, 32, 32)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            insets = Insets(12, 12, 12, 12)
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
        }

        val title = JLabel("pShare", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 36f)
            foreground = Color(0x3B, 0x82, 0xF6)
        }
        val subtitle = JLabel("Secure, direct file & text transfer for Windows PC", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.PLAIN, 15f)
        }

        val btnGoSend = JButton("Send Files or Text").apply {
            font = font.deriveFont(Font.BOLD, 18f)
            preferredSize = Dimension(320, 60)
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
            background = Color(0x25, 0x63, 0xEB)
            foreground = Color.WHITE
            addActionListener {
                cardLayout.show(mainContainer, "SEND")
            }
        }

        val btnGoReceive = JButton("Receive Files or Text").apply {
            font = font.deriveFont(Font.BOLD, 18f)
            preferredSize = Dimension(320, 60)
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
            background = Color(0x10, 0xB9, 0x81)
            foreground = Color.WHITE
            addActionListener {
                openReceiveMode()
            }
        }

        homePanel.add(title, gbc)
        homePanel.add(subtitle, gbc)
        homePanel.add(Box.createVerticalStrut(24), gbc)
        homePanel.add(btnGoSend, gbc)
        homePanel.add(btnGoReceive, gbc)
    }

    // -------------------------------- SEND MODE --------------------------------

    private val sendQrContainer = JPanel(BorderLayout())
    private val sendQrImageLabel = JLabel("", SwingConstants.CENTER)
    private val sendItemsTextArea = JTextArea(5, 40).apply {
        isEditable = false
        font = font.deriveFont(12f)
    }
    private val btnSendToggleScan = JButton("Scan Receiver QR Code (Frontal Camera)")

    private fun setupSendPanel() {
        sendPanel.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

        // Top bar
        val topBar = JPanel(BorderLayout()).apply {
            val btnBack = JButton("← Home").apply {
                addActionListener {
                    cleanupSend()
                    cardLayout.show(mainContainer, "HOME")
                }
            }
            val title = JLabel("Send", SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.BOLD, 20f)
            }
            add(btnBack, BorderLayout.WEST)
            add(title, BorderLayout.CENTER)
            add(Box.createHorizontalStrut(btnBack.preferredSize.width), BorderLayout.EAST)
        }
        sendPanel.add(topBar, BorderLayout.NORTH)

        // Center: Selection & QR Display
        val centerCardLayout = CardLayout()
        val centerContainer = JPanel(centerCardLayout)

        // 1) File picking & QR panel
        val sendMainView = JPanel(BorderLayout(12, 12)).apply {
            val fileControls = JPanel().apply {
                val btnAddFiles = JButton("Choose Files...").apply {
                    addActionListener {
                        val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
                        if (chooser.showOpenDialog(this@MainWindow) == JFileChooser.APPROVE_OPTION) {
                            val items = DesktopItemResolver.resolve(chooser.selectedFiles.toList())
                            sendItems.addAll(items)
                            updateSendItemsList()
                            startSenderServerAndQr()
                        }
                    }
                }
                val btnAddFolder = JButton("Choose Folder...").apply {
                    addActionListener {
                        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                        if (chooser.showOpenDialog(this@MainWindow) == JFileChooser.APPROVE_OPTION) {
                            val items = DesktopItemResolver.resolve(listOf(chooser.selectedFile))
                            sendItems.addAll(items)
                            updateSendItemsList()
                            startSenderServerAndQr()
                        }
                    }
                }
                val btnAddText = JButton("Send Text...").apply {
                    addActionListener {
                        val txt = JOptionPane.showInputDialog(this@MainWindow, "Enter text to send:")
                        if (!txt.isNullOrBlank()) {
                            sendItems.add(TextItem(txt))
                            updateSendItemsList()
                            startSenderServerAndQr()
                        }
                    }
                }
                val btnClear = JButton("Clear").apply {
                    addActionListener {
                        sendItems.clear()
                        updateSendItemsList()
                        cleanupSend()
                        sendQrImageLabel.icon = null
                    }
                }
                add(btnAddFiles)
                add(btnAddFolder)
                add(btnAddText)
                add(btnClear)
            }

            val qrBox = JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder("Pairing QR (Scan this with the receiving device)")
                add(sendQrImageLabel, BorderLayout.CENTER)
            }

            add(fileControls, BorderLayout.NORTH)
            add(qrBox, BorderLayout.CENTER)
            add(JScrollPane(sendItemsTextArea).apply {
                border = BorderFactory.createTitledBorder("Selected Items")
            }, BorderLayout.SOUTH)
        }

        // 2) Reverse scan camera view for Sender
        val sendCameraView = JPanel(BorderLayout(8, 8)).apply {
            add(JLabel("Point your frontal webcam at the Receiver's QR code", SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }, BorderLayout.NORTH)
            add(cameraPreview, BorderLayout.CENTER)
            val btnCancelScan = JButton("Cancel Webcam Scan").apply {
                addActionListener {
                    stopCamera()
                    centerCardLayout.show(centerContainer, "MAIN")
                }
            }
            add(btnCancelScan, BorderLayout.SOUTH)
        }

        centerContainer.add(sendMainView, "MAIN")
        centerContainer.add(sendCameraView, "CAMERA")
        sendPanel.add(centerContainer, BorderLayout.CENTER)

        // Bottom toggle button for reverse scan
        val bottomBar = JPanel().apply {
            btnSendToggleScan.apply {
                font = font.deriveFont(Font.BOLD, 14f)
                addActionListener {
                    if (sendItems.isEmpty()) {
                        JOptionPane.showMessageDialog(this@MainWindow, "Please select at least one file or enter text first.")
                        return@addActionListener
                    }
                    centerCardLayout.show(centerContainer, "CAMERA")
                    startCameraForSender()
                }
            }
            add(btnSendToggleScan)
        }
        sendPanel.add(bottomBar, BorderLayout.SOUTH)
    }

    private fun updateSendItemsList() {
        if (sendItems.isEmpty()) {
            sendItemsTextArea.text = "No items selected."
            return
        }
        val sb = StringBuilder()
        var totalBytes = 0L
        for ((idx, itm) in sendItems.withIndex()) {
            val sz = if (itm.info.size >= 0) " (${formatBytes(itm.info.size)})" else ""
            sb.append("${idx + 1}. [${itm.info.mime}] ${itm.info.name}$sz\n")
            if (itm.info.size > 0) totalBytes += itm.info.size
        }
        sb.append("\nTotal: ${sendItems.size} items (${formatBytes(totalBytes)})")
        sendItemsTextArea.text = sb.toString()
    }

    private fun startSenderServerAndQr() {
        if (sendItems.isEmpty()) return
        cleanupSend()
        transferClaimed.set(false)

        val (sessionId, key) = Pairing.newSecrets()
        lanServer = LanServer { link ->
            if (transferClaimed.compareAndSet(false, true)) {
                startTransferAsSender(link, key, sessionId)
            } else {
                link.close()
            }
        }.apply { start() }
        val port = lanServer!!.port
        val addrs = Lan.localIpv4().map { it.address }
        val pairing = Pairing(
            sessionId = sessionId,
            key = key,
            deviceName = myName,
            lanPort = port,
            lanAddresses = addrs,
            bluetooth = false,
            internet = false,
            isReceiver = false
        )
        val qrBytes = pairing.encode()
        val img = DesktopQr.render(qrBytes, 280)
        sendQrImageLabel.icon = ImageIcon(img)
    }

    private fun startCameraForSender() {
        stopCamera()
        camera = DesktopCamera(
            onFrame = { frame ->
                cameraPreview.updateFrame(frame)
            },
            onCode = { codeBytes ->
                val pairing = Pairing.decode(codeBytes)
                if (pairing != null && pairing.isReceiver) {
                    stopCamera()
                    SwingUtilities.invokeLater {
                        connectToReceiver(pairing)
                    }
                } else if (pairing != null && !pairing.isReceiver) {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this, "Scanned a Sender code, but we are in Send mode. To receive, go to Receive.")
                    }
                }
            },
            onError = { errMsg ->
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this, errMsg)
                }
            }
        )
        camera?.start()
    }

    private fun connectToReceiver(pairing: Pairing) {
        if (!transferClaimed.compareAndSet(false, true)) return
        showTransferScreen("Connecting to Receiver (${pairing.deviceName})...")
        val addrs = pairing.lanAddresses.mapNotNull {
            runCatching { InetAddress.getByAddress(it) }.getOrNull()
        }
        lanClient = LanClient(addrs, pairing.lanPort, onLink = { link ->
            startTransferAsSender(link, pairing.key, pairing.sessionId, isClient = true)
        }).apply { start() }
    }

    private fun startTransferAsSender(link: Link, key: ByteArray, sessionId: ByteArray, isClient: Boolean = false) {
        showTransferScreen("Sending to peer...")
        thread(name = "desktop-sender-worker") {
            try {
                val ch = SecureChannel(key, sessionId, initiator = isClient, input = link.input, output = link.output)
                val peer = Protocol.readPeer(ch)
                SwingUtilities.invokeLater {
                    lblTransferDetail.text = "Connected to ${peer.name} (${link.transport})"
                }
                val itemsToSend = Protocol.itemsFor(sendItems, peer.features)
                val totalBytes = itemsToSend.map { it.info }.knownTotal()

                val listener = object : TransferListener {
                    override fun onProgress(done: Long, total: Long) {
                        SwingUtilities.invokeLater {
                            val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                            progressBar.value = pct
                            lblProgressStats.text = "${formatBytes(done)} / ${formatBytes(total)} ($pct%)"
                        }
                    }
                    override fun onItemDone(index: Int, info: x.vladgba.share.core.ItemInfo) {
                        SwingUtilities.invokeLater {
                            lblTransferDetail.text = "Sent: ${info.name}"
                        }
                    }
                }

                Protocol.send(ch, myName, itemsToSend, listener, chunk = link.chunk, window = link.window)
                SwingUtilities.invokeLater {
                    lblTransferTitle.text = "Transfer Finished!"
                    lblTransferDetail.text = "All items sent successfully."
                    progressBar.value = 100
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    lblTransferTitle.text = "Transfer Error"
                    lblTransferDetail.text = e.message ?: "Transfer failed"
                }
            } finally {
                link.close()
            }
        }
    }

    // -------------------------------- RECEIVE MODE --------------------------------

    private val receiveCardLayout = CardLayout()
    private val receiveCenterContainer = JPanel(receiveCardLayout)
    private val receiveQrImageLabel = JLabel("", SwingConstants.CENTER)
    private val btnReceiveToggle = JButton("Reverse Scan: Show My QR Code on Screen")

    private fun setupReceivePanel() {
        receivePanel.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

        // Top bar
        val topBar = JPanel(BorderLayout()).apply {
            val btnBack = JButton("← Home").apply {
                addActionListener {
                    cleanupReceive()
                    cardLayout.show(mainContainer, "HOME")
                }
            }
            val title = JLabel("Receive", SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.BOLD, 20f)
            }
            add(btnBack, BorderLayout.WEST)
            add(title, BorderLayout.CENTER)
            add(Box.createHorizontalStrut(btnBack.preferredSize.width), BorderLayout.EAST)
        }
        receivePanel.add(topBar, BorderLayout.NORTH)

        // 1) Camera view (Default: uses frontal webcam to scan Sender's QR)
        val cameraView = JPanel(BorderLayout(8, 8)).apply {
            add(JLabel("Point your frontal webcam at the Sender's QR code", SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }, BorderLayout.NORTH)
            add(cameraPreview, BorderLayout.CENTER)
        }

        // 2) Reverse scan view: PC displays its own QR code on monitor for sender to scan
        val qrView = JPanel(BorderLayout(12, 12)).apply {
            val qrBox = JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder("Receiver Pairing QR (Sender scans this code)")
                add(receiveQrImageLabel, BorderLayout.CENTER)
            }
            add(JLabel("The sender can now scan this code using their phone's camera", SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.PLAIN, 14f)
            }, BorderLayout.NORTH)
            add(qrBox, BorderLayout.CENTER)
        }

        receiveCenterContainer.add(cameraView, "CAMERA")
        receiveCenterContainer.add(qrView, "QR")
        receivePanel.add(receiveCenterContainer, BorderLayout.CENTER)

        // Bottom toggle button
        val bottomBar = JPanel().apply {
            btnReceiveToggle.apply {
                font = font.deriveFont(Font.BOLD, 14f)
                addActionListener {
                    if (btnReceiveToggle.text.contains("Show My QR")) {
                        stopCamera()
                        startReceiverServerAndQr()
                        receiveCardLayout.show(receiveCenterContainer, "QR")
                        btnReceiveToggle.text = "Switch to Webcam Scanner (Frontal Camera)"
                    } else {
                        cleanupReceive()
                        receiveCardLayout.show(receiveCenterContainer, "CAMERA")
                        btnReceiveToggle.text = "Reverse Scan: Show My QR Code on Screen"
                        startCameraForReceiver()
                    }
                }
            }
            add(btnReceiveToggle)
        }
        receivePanel.add(bottomBar, BorderLayout.SOUTH)
    }

    private fun openReceiveMode() {
        cleanupReceive()
        cardLayout.show(mainContainer, "RECEIVE")
        receiveCardLayout.show(receiveCenterContainer, "CAMERA")
        btnReceiveToggle.text = "Reverse Scan: Show My QR Code on Screen"
        startCameraForReceiver()
    }

    private fun startCameraForReceiver() {
        stopCamera()
        transferClaimed.set(false)
        camera = DesktopCamera(
            onFrame = { frame ->
                cameraPreview.updateFrame(frame)
            },
            onCode = { codeBytes ->
                val pairing = Pairing.decode(codeBytes)
                if (pairing != null && !pairing.isReceiver) {
                    stopCamera()
                    SwingUtilities.invokeLater {
                        connectToSender(pairing)
                    }
                } else if (pairing != null && pairing.isReceiver) {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this, "Scanned another receiver's QR code. A sender code is required.")
                    }
                }
            },
            onError = { errMsg ->
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this, errMsg)
                }
            }
        )
        camera?.start()
    }

    private fun connectToSender(pairing: Pairing) {
        if (!transferClaimed.compareAndSet(false, true)) return
        showTransferScreen("Connecting to ${pairing.deviceName}...")
        val addrs = pairing.lanAddresses.mapNotNull {
            runCatching { InetAddress.getByAddress(it) }.getOrNull()
        }
        lanClient = LanClient(addrs, pairing.lanPort, onLink = { link ->
            startTransferAsReceiver(link, pairing.key, pairing.sessionId, isClient = true)
        }).apply { start() }
    }

    private fun startReceiverServerAndQr() {
        cleanupReceive()
        transferClaimed.set(false)

        val (sessionId, key) = Pairing.newSecrets()
        lanServer = LanServer { link ->
            if (transferClaimed.compareAndSet(false, true)) {
                startTransferAsReceiver(link, key, sessionId, isClient = false)
            } else {
                link.close()
            }
        }.apply { start() }
        val port = lanServer!!.port
        val addrs = Lan.localIpv4().map { it.address }
        val pairing = Pairing(
            sessionId = sessionId,
            key = key,
            deviceName = myName,
            lanPort = port,
            lanAddresses = addrs,
            bluetooth = false,
            internet = false,
            isReceiver = true
        )
        val qrBytes = pairing.encode()
        val img = DesktopQr.render(qrBytes, 280)
        receiveQrImageLabel.icon = ImageIcon(img)
    }

    private fun startTransferAsReceiver(link: Link, key: ByteArray, sessionId: ByteArray, isClient: Boolean) {
        showTransferScreen("Connected. Waiting for offer...")
        thread(name = "desktop-receiver-worker") {
            try {
                val ch = SecureChannel(key, sessionId, initiator = isClient, input = link.input, output = link.output)
                val offer = Protocol.requestOffer(ch, myName)
                val totalBytes = offer.items.knownTotal()

                SwingUtilities.invokeLater {
                    lblTransferDetail.text = "Receiving from ${offer.peerName} (${offer.items.size} items)"
                }

                val sink = DesktopSink(
                    onItemStart = { idx, info ->
                        SwingUtilities.invokeLater {
                            lblTransferDetail.text = "Receiving: ${info.name}"
                        }
                    },
                    onAllComplete = {
                        SwingUtilities.invokeLater {
                            lblTransferTitle.text = "Transfer Finished!"
                            lblTransferDetail.text = "All items received in Downloads/pShare"
                            progressBar.value = 100
                            btnOpenFolder.isVisible = true
                        }
                    }
                )

                val listener = object : TransferListener {
                    override fun onProgress(done: Long, total: Long) {
                        SwingUtilities.invokeLater {
                            val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                            progressBar.value = pct
                            lblProgressStats.text = "${formatBytes(done)} / ${formatBytes(total)} ($pct%)"
                        }
                    }
                }

                Protocol.receive(ch, offer, sink, listener)
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    lblTransferTitle.text = "Transfer Error"
                    lblTransferDetail.text = e.message ?: "Transfer failed"
                }
            } finally {
                link.close()
            }
        }
    }

    // -------------------------------- TRANSFER SCREEN --------------------------------

    private fun setupTransferPanel() {
        transferPanel.border = BorderFactory.createEmptyBorder(32, 32, 32, 32)

        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            lblTransferTitle.font = lblTransferTitle.font.deriveFont(Font.BOLD, 22f)
            lblTransferDetail.font = lblTransferDetail.font.deriveFont(Font.PLAIN, 15f)
            progressBar.preferredSize = Dimension(400, 24)
            progressBar.isStringPainted = true

            lblTransferTitle.alignmentX = Component.CENTER_ALIGNMENT
            lblTransferDetail.alignmentX = Component.CENTER_ALIGNMENT
            progressBar.alignmentX = Component.CENTER_ALIGNMENT
            lblProgressStats.alignmentX = Component.CENTER_ALIGNMENT
            btnOpenFolder.alignmentX = Component.CENTER_ALIGNMENT
            btnOpenFolder.isVisible = false
            btnOpenFolder.addActionListener {
                try {
                    val dir = File(System.getProperty("user.home"), "Downloads/pShare")
                    dir.mkdirs()
                    Desktop.getDesktop().open(dir)
                } catch (_: Exception) {}
            }

            add(lblTransferTitle)
            add(Box.createVerticalStrut(12))
            add(lblTransferDetail)
            add(Box.createVerticalStrut(24))
            add(progressBar)
            add(Box.createVerticalStrut(8))
            add(lblProgressStats)
            add(Box.createVerticalStrut(24))
            add(btnOpenFolder)
        }

        val btnBackHome = JButton("Back to Home").apply {
            addActionListener {
                cleanupSend()
                cleanupReceive()
                cardLayout.show(mainContainer, "HOME")
            }
        }

        transferPanel.add(centerPanel, BorderLayout.CENTER)
        transferPanel.add(btnBackHome, BorderLayout.SOUTH)
    }

    private fun showTransferScreen(initialText: String) {
        SwingUtilities.invokeLater {
            lblTransferTitle.text = "Transferring..."
            lblTransferDetail.text = initialText
            progressBar.value = 0
            lblProgressStats.text = "0 MB / 0 MB"
            btnOpenFolder.isVisible = false
            cardLayout.show(mainContainer, "TRANSFER")
        }
    }

    // -------------------------------- HELPERS --------------------------------

    private fun stopCamera() {
        camera?.stop()
        camera = null
    }

    private fun cleanupSend() {
        stopCamera()
        try { lanServer?.stop() } catch (_: Exception) {}
        try { lanClient?.stop() } catch (_: Exception) {}
        lanServer = null
        lanClient = null
    }

    private fun cleanupReceive() {
        stopCamera()
        try { lanServer?.stop() } catch (_: Exception) {}
        try { lanClient?.stop() } catch (_: Exception) {}
        lanServer = null
        lanClient = null
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }
}

class CameraPreviewPanel : JPanel() {
    private var currentImage: BufferedImage? = null

    init {
        preferredSize = Dimension(480, 360)
        background = Color.BLACK
    }

    fun updateFrame(image: BufferedImage) {
        currentImage = image
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val img = currentImage ?: return
        val panelW = width
        val panelH = height
        val imgW = img.width
        val imgH = img.height
        val scale = minOf(panelW.toDouble() / imgW, panelH.toDouble() / imgH)
        val drawW = (imgW * scale).toInt()
        val drawH = (imgH * scale).toInt()
        val x = (panelW - drawW) / 2
        val y = (panelH - drawH) / 2
        g.drawImage(img, x, y, drawW, drawH, null)
    }
}
