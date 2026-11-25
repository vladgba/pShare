package x.vladgba.sync.core.net

import x.vladgba.sync.core.crypto.Crypto
import x.vladgba.sync.core.crypto.DeviceIdentity
import x.vladgba.sync.core.util.Hashing
import x.vladgba.sync.core.util.JObj
import x.vladgba.sync.core.util.Json
import x.vladgba.sync.core.util.b64
import x.vladgba.sync.core.util.unb64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HandshakeException(message: String) : IOException(message)

data class HandshakeHello(
    val protocol: String,
    val name: String,
    val platform: String,
    val identityKey: String,
    val ephemeralKey: String,
    val nonce: String,
) {
    fun toJson() = mapOf(
        "protocol" to protocol, "name" to name, "platform" to platform,
        "identityKey" to identityKey, "ephemeralKey" to ephemeralKey, "nonce" to nonce,
    )

    companion object {
        fun fromJson(o: JObj) = HandshakeHello(
            o.str("protocol"), o.str("name"), o.str("platform"), o.str("identityKey"), o.str("ephemeralKey"), o.str("nonce"),
        )
    }
}

class Frame(val type: Int, val payload: ByteArray)

/**
 * Authenticated, encrypted framing over a TCP socket.
 *
 * Handshake (SIGMA-style):
 *  1. Both sides exchange [HandshakeHello] in clear: identity key, ephemeral P-256 key, nonce.
 *  2. transcript = SHA-256(protocol ‖ helloInitiator ‖ helloResponder)
 *  3. Session keys = HKDF(salt = transcript, ECDH(ephemeral, peer ephemeral)), one per direction.
 *  4. Each side sends, encrypted, an ECDSA signature by its identity key over (role ‖ transcript).
 *
 * The peer's device ID is derived from its identity key, so it cannot be spoofed. The short
 * authentication string [sas] is shown on both screens during pairing; a man in the middle
 * would produce different codes on each device.
 *
 * Frames: int32 length ‖ AES-256-GCM(type ‖ payload), nonce = per-direction counter.
 */
class SecureChannel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendKey: SecretKeySpec,
    private val recvKey: SecretKeySpec,
    val peerHello: HandshakeHello,
    val sas: String,
) : Closeable {
    val peerDeviceId: String = DeviceIdentity.deviceIdOf(peerHello.identityKey.unb64())
    val peerPublicKey: String get() = peerHello.identityKey
    val remoteHost: String? = socket.inetAddress?.hostAddress

    private var sendCounter = 0L
    private var recvCounter = 0L
    private val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val recvCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val writeLock = Any()

    fun send(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        synchronized(writeLock) {
            val plain = ByteArray(length + 1)
            plain[0] = type.toByte()
            System.arraycopy(payload, offset, plain, 1, length)
            sendCipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendCounter++)))
            val ct = sendCipher.doFinal(plain)
            output.writeInt(ct.size)
            output.write(ct)
            output.flush()
        }
    }

    /** Blocking; call from a single reader thread only. */
    fun receive(): Frame {
        val len = input.readInt()
        if (len < 17 || len > MAX_FRAME) throw IOException("Bad frame length $len")
        val ct = ByteArray(len)
        input.readFully(ct)
        recvCipher.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(recvCounter++)))
        val plain = try {
            recvCipher.doFinal(ct)
        } catch (e: Exception) {
            throw IOException("Frame authentication failed", e)
        }
        return Frame(plain[0].toInt(), plain.copyOfRange(1, plain.size))
    }

    fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val PROTOCOL = "LocalBridge/1"
        const val MAX_FRAME = 4 * 1024 * 1024
        const val FRAME_AUTH = 0

        private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

        fun handshake(socket: Socket, identity: DeviceIdentity, initiator: Boolean, platform: String): SecureChannel {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            val eph = Crypto.newEcKeyPair()
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val myHello = HandshakeHello(PROTOCOL, identity.name, platform, identity.publicKeyB64, eph.public.encoded.b64(), nonce.b64())
            val myBytes = Json.stringify(myHello.toJson()).toByteArray()

            fun writePlain(b: ByteArray) {
                output.writeInt(b.size); output.write(b); output.flush()
            }

            fun readPlain(): ByteArray {
                val n = input.readInt()
                if (n !in 1..8192) throw HandshakeException("Bad hello length $n")
                return ByteArray(n).also { input.readFully(it) }
            }

            val peerBytes: ByteArray
            if (initiator) {
                writePlain(myBytes); peerBytes = readPlain()
            } else {
                peerBytes = readPlain(); writePlain(myBytes)
            }
            val peerHello = try {
                HandshakeHello.fromJson(Json.parseObject(peerBytes.decodeToString()))
            } catch (e: Exception) {
                throw HandshakeException("Not a LocalBridge peer")
            }
            if (peerHello.protocol != PROTOCOL) throw HandshakeException("Unsupported protocol ${peerHello.protocol}")

            val (iBytes, rBytes) = if (initiator) myBytes to peerBytes else peerBytes to myBytes
            val transcript = Hashing.sha256(
                PROTOCOL.toByteArray() + ByteBuffer.allocate(4).putInt(iBytes.size).array() + iBytes +
                    ByteBuffer.allocate(4).putInt(rBytes.size).array() + rBytes
            )
            val peerIdentity = Crypto.publicKey(peerHello.identityKey.unb64())
            val shared = Crypto.ecdh(eph.private, Crypto.publicKey(peerHello.ephemeralKey.unb64()))
            val kI2R = SecretKeySpec(Crypto.hkdf(transcript, shared, "LocalBridge i2r"), "AES")
            val kR2I = SecretKeySpec(Crypto.hkdf(transcript, shared, "LocalBridge r2i"), "AES")
            val sasBytes = Hashing.sha256("sas".toByteArray() + transcript)
            val sasNum = (ByteBuffer.wrap(sasBytes).int.toLong() and 0xffffffffL) % 1_000_000
            val channel = SecureChannel(
                socket, input, output,
                sendKey = if (initiator) kI2R else kR2I,
                recvKey = if (initiator) kR2I else kI2R,
                peerHello = peerHello,
                sas = sasNum.toString().padStart(6, '0'),
            )

            val myRole = if (initiator) "I" else "R"
            val peerRole = if (initiator) "R" else "I"
            channel.send(FRAME_AUTH, identity.sign("auth$myRole".toByteArray() + transcript))
            val auth = channel.receive()
            if (auth.type != FRAME_AUTH || !Crypto.verify(peerIdentity, "auth$peerRole".toByteArray() + transcript, auth.payload)) {
                channel.close()
                throw HandshakeException("Peer failed to prove its identity")
            }
            return channel
        }
    }
}
