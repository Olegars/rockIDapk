package id.rock.messenger

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey

/**
 * Тот же конверт, что у Windows-клиента: ECDH P-256, ключ SHA-256 от X, AES-256-GCM.
 * Публичный ключ — 64 байта X||Y, текст начинается с "e2e:".
 */
object SecretBox {
    data class Identity(val privatePkcs8: String, val publicRaw: String)

    fun generate(): Identity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = generator.generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        val raw = mag(point.affineX) + mag(point.affineY)
        return Identity(
            Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP),
            Base64.encodeToString(raw, Base64.NO_WRAP),
        )
    }

    fun agree(privatePkcs8: String, peerRaw: String): ByteArray {
        val factory = KeyFactory.getInstance("EC")
        val mine = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privatePkcs8, Base64.NO_WRAP)))
        val peerBytes = Base64.decode(peerRaw, Base64.NO_WRAP)
        if (peerBytes.size != 64) return ByteArray(0)
        val params = java.security.AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val spec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, peerBytes.copyOfRange(0, 32)), BigInteger(1, peerBytes.copyOfRange(32, 64)))
        val peer = factory.generatePublic(ECPublicKeySpec(point, spec))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(mine)
        agreement.doPhase(peer, true)
        return MessageDigest.getInstance("SHA-256").digest(pad32(agreement.generateSecret()))
    }

    fun seal(key: ByteArray, plain: String): String {
        if (key.size != 32) return ""
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES") as SecretKey, GCMParameterSpec(128, nonce))
        val boxed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "e2e:" + Base64.encodeToString(nonce + boxed, Base64.NO_WRAP)
    }

    fun openGcm(key: ByteArray, nonce: ByteArray, cipherAndTag: ByteArray): String {
        if (key.size != 32 || nonce.size != 12 || cipherAndTag.size < 16) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            String(cipher.doFinal(cipherAndTag), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun open(key: ByteArray, wire: String): String {
        if (key.size != 32 || !wire.startsWith("e2e:")) return ""
        val packed = Base64.decode(wire.removePrefix("e2e:"), Base64.NO_WRAP)
        if (packed.size < 28) return ""
        val nonce = packed.copyOfRange(0, 12)
        val boxed = packed.copyOfRange(12, packed.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            String(cipher.doFinal(boxed), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun mag(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val stripped = if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        if (stripped.size == 32) return stripped
        if (stripped.size > 32) return stripped.copyOfRange(stripped.size - 32, stripped.size)
        return ByteArray(32 - stripped.size) + stripped
    }

    private fun pad32(secret: ByteArray): ByteArray {
        if (secret.size == 32) return secret
        if (secret.size > 32) return secret.copyOfRange(secret.size - 32, secret.size)
        return ByteArray(32 - secret.size) + secret
    }
}
