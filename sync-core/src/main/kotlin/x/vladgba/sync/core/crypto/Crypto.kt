package x.vladgba.sync.core.crypto

import x.vladgba.sync.core.util.AtomicFiles
import x.vladgba.sync.core.util.Base32
import x.vladgba.sync.core.util.Hashing
import x.vladgba.sync.core.util.Json
import x.vladgba.sync.core.util.b64
import x.vladgba.sync.core.util.unb64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Long-term device identity: an EC P-256 key pair. The device ID is derived from the public key. */
class DeviceIdentity(val name: String, val keyPair: KeyPair) {
    val publicKeyBytes: ByteArray = keyPair.public.encoded
    val publicKeyB64: String = publicKeyBytes.b64()
    val deviceId: String = deviceIdOf(publicKeyBytes)

    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(data)
        sign()
    }

    fun withName(newName: String) = DeviceIdentity(newName, keyPair)

    companion object {
        /** 16 base32 chars (80 bits) of SHA-256(public key). */
        fun deviceIdOf(publicKey: ByteArray): String = Base32.encode(Hashing.sha256(publicKey).copyOf(10))

        fun generate(name: String) = DeviceIdentity(name, Crypto.newEcKeyPair())

        fun format(deviceId: String) = deviceId.chunked(4).joinToString("-")
    }
}

object Crypto {
    fun newEcKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun publicKey(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    fun privateKey(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(data)
            verify(signature)
        }
    } catch (e: Exception) {
        false
    }

    fun ecdh(private: PrivateKey, public: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(private)
        doPhase(public, true)
        generateSecret()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869), single-block expand (≤ 32 bytes). */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, length: Int = 32): ByteArray {
        require(length <= 32)
        val prk = hmac(salt, ikm)
        return hmac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(length)
    }
}

object IdentityStore {
    fun loadOrCreate(file: File, defaultName: String): DeviceIdentity {
        if (file.exists()) {
            val o = Json.parseObject(file.readText())
            val keys = KeyPair(Crypto.publicKey(o.str("publicKey").unb64()), Crypto.privateKey(o.str("privateKey").unb64()))
            return DeviceIdentity(o.str("name"), keys)
        }
        return DeviceIdentity.generate(defaultName).also { save(file, it) }
    }

    fun save(file: File, identity: DeviceIdentity) {
        val data = mapOf(
            "name" to identity.name,
            "privateKey" to identity.keyPair.private.encoded.b64(),
            "publicKey" to identity.publicKeyB64,
        )
        AtomicFiles.write(file, Json.stringify(data).toByteArray())
    }
}
