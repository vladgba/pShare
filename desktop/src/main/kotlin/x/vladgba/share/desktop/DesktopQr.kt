package x.vladgba.share.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.image.BufferedImage

object DesktopQr {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.CHARACTER_SET to "ISO-8859-1",
        DecodeHintType.TRY_HARDER to true
    )

    fun render(data: ByteArray, size: Int = 400): BufferedImage {
        val isoString = String(data, Charsets.ISO_8859_1)
        val encodeHints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(isoString, BarcodeFormat.QR_CODE, size, size, encodeHints)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val black = Color.BLACK.rgb
        val white = Color.WHITE.rgb
        for (x in 0 until size) {
            for (y in 0 until size) {
                img.setRGB(x, y, if (matrix.get(x, y)) black else white)
            }
        }
        return img
    }

    fun decode(img: BufferedImage): ByteArray? {
        return try {
            val source = BufferedImageLuminanceSource(img)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val res = reader.decode(bitmap, hints)
            res.text.toByteArray(Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        }
    }
}
