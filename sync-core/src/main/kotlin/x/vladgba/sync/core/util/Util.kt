package x.vladgba.sync.core.util

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

object Hashing {
    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
    fun sha256(bytes: ByteArray): ByteArray = sha256().digest(bytes)
    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun sha256Hex(input: InputStream): String {
        val md = sha256()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0xf])
    }
    return sb.toString()
}

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}

object AtomicFiles {
    /** Writes [bytes] to a temp file next to [target] and moves it into place. */
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            move(tmp, target)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
