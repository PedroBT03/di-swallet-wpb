package di.swallet.wpb.transactionlog.crypto

import di.swallet.wpb.config.TransactionLogProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class TransactionLogCrypto(
    properties: TransactionLogProperties,
) {
    private val encryptionKey: ByteArray = decodeKey(properties.encryptionKey, "encryption-key")
    private val integrityKey: ByteArray = decodeKey(properties.integrityKey, "integrity-key")

    fun holderEncryptionKey(holderId: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(encryptionKey, "HmacSHA256"))
        return mac.doFinal("txlog-dek:$holderId".toByteArray(Charsets.UTF_8))
    }

    fun encrypt(holderId: String, plaintext: ByteArray): String {
        val key = holderEncryptionKey(holderId)
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(holderId: String, encoded: String): ByteArray {
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.size > 12) { "Invalid transaction log ciphertext" }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val key = holderEncryptionKey(holderId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun integrityMac(
        transactionId: String,
        holderId: String,
        occurredAtEpochMillis: Long,
        transactionType: String,
        transactionResult: String,
        payloadCiphertext: String,
    ): String {
        val canonical = listOf(
            transactionId,
            holderId,
            occurredAtEpochMillis.toString(),
            transactionType,
            transactionResult,
            payloadCiphertext,
        ).joinToString("|")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(integrityKey, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    }

    fun contentHash(data: ByteArray, algorithm: String = "SHA-256"): String {
        val digest = MessageDigest.getInstance(algorithm)
        return Base64.getEncoder().encodeToString(digest.digest(data))
    }

    private fun decodeKey(encoded: String, label: String): ByteArray {
        val value = encoded.ifBlank {
            // Dev fallback: deterministic keys for local runs without explicit config.
            Base64.getEncoder().encodeToString("$label-dev-only-32-bytes-key!!".toByteArray(Charsets.UTF_8).copyOf(32))
        }
        return Base64.getDecoder().decode(value).also {
            require(it.size == 32) { "wpb.transaction-log.$label must decode to 32 bytes" }
        }
    }
}
