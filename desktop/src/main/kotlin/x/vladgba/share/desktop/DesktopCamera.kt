package x.vladgba.share.desktop

import com.github.sarxos.webcam.Webcam
import com.github.sarxos.webcam.WebcamResolution
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures frames from the default frontal/integrated PC webcam
 * and decodes QR codes continuously.
 */
class DesktopCamera(
    private val onFrame: (BufferedImage) -> Unit,
    private val onCode: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private var webcam: Webcam? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        worker = thread(name = "desktop-camera-thread", isDaemon = true) {
            try {
                val cam = Webcam.getDefault()
                if (cam == null) {
                    onError("No webcam found on this PC.")
                    running.set(false)
                    return@thread
                }
                webcam = cam
                cam.viewSize = WebcamResolution.VGA.size // 640x480 is standard, fast & sharp for QR
                if (!cam.open()) {
                    onError("Failed to open webcam.")
                    running.set(false)
                    return@thread
                }

                var lastDecodedCode: String? = null
                var lastDecodedTime = 0L

                while (running.get() && cam.isOpen) {
                    val img = cam.image
                    if (img != null) {
                        onFrame(img)
                        val codeBytes = DesktopQr.decode(img)
                        if (codeBytes != null) {
                            val codeStr = String(codeBytes, Charsets.ISO_8859_1)
                            val now = System.currentTimeMillis()
                            // Avoid repeatedly triggering the exact same frame within 1.5s
                            if (codeStr != lastDecodedCode || now - lastDecodedTime > 1500) {
                                lastDecodedCode = codeStr
                                lastDecodedTime = now
                                onCode(codeBytes)
                            }
                        }
                    }
                    try {
                        Thread.sleep(30) // ~30 fps cap
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) onError("Camera error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            webcam?.close()
        } catch (_: Exception) {}
        webcam = null
    }
}
