/**
 * AES-GCM encryption, decryption, and integrity MAC for transaction log payloads.
 */

package di.swallet.wpb.transactionlog.crypto

import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.security.HolderLogKeyContext
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts TS10 payloads per holder and verifies stored integrity MACs. */
@Component
class TransactionLogCrypto(
    private val properties: TransactionLogProperties,
    private val holderLogKeyContext: HolderLogKeyContext,
) {
    private val integrityKey: ByteArray = decodeRequiredKey(properties.integrityKey, "integrity-key")
    private val serverEncryptionKey: ByteArray by lazy {
        decodeRequiredKey(properties.encryptionKey, "encryption-key")
    }

    /** Returns the configured active DEK mode from properties. */
    fun activeDekMode(): TransactionLogDekMode = properties.resolvedDekMode()

    /** Encrypts plaintext with AES-GCM using a per-holder key derived from the active DEK mode. */
    fun encrypt(holderId: String, plaintext: ByteArray, dekMode: TransactionLogDekMode = activeDekMode()): String {
        val key = holderEncryptionKey(holderId, dekMode)
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    /** Decrypts a stored ciphertext blob for the given holder and DEK mode. */
    fun decrypt(holderId: String, encoded: String, dekMode: TransactionLogDekMode): ByteArray {
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.size > 12) { "Invalid transaction log ciphertext" }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val key = holderEncryptionKey(holderId, dekMode)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Computes HMAC-SHA256 over canonical entry fields for tamper detection. */
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

    /** Returns Base64-encoded digest of data for signing/sealing log entries. */
    fun contentHash(data: ByteArray, algorithm: String = "SHA-256"): String {
        val digest = MessageDigest.getInstance(algorithm)
        return Base64.getEncoder().encodeToString(digest.digest(data))
    }

    /** Returns false for HOLDER mode when no holder log key is present in the request context. */
    fun canDecrypt(dekMode: TransactionLogDekMode): Boolean =
        dekMode == TransactionLogDekMode.SERVER || holderLogKeyContext.currentKey() != null

    /** Selects server-derived or holder-provided AES key for the given DEK mode. */
    private fun holderEncryptionKey(holderId: String, dekMode: TransactionLogDekMode): ByteArray =
        when (dekMode) {
            TransactionLogDekMode.SERVER -> serverDerivedKey(holderId)
            TransactionLogDekMode.HOLDER -> holderLogKeyContext.requireKey(dekMode)
        }

    /** Derives per-holder AES key from server encryption key via HMAC. */
    private fun serverDerivedKey(holderId: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(serverEncryptionKey, "HmacSHA256"))
        return mac.doFinal("txlog-dek:$holderId".toByteArray(Charsets.UTF_8))
    }

    /** Decodes and validates a configured Base64 key (must be 32 bytes). */
    private fun decodeRequiredKey(encoded: String, label: String): ByteArray {
        require(encoded.isNotBlank()) {
            "wpb.transaction-log.$label must be configured (empty value rejected)"
        }
        return Base64.getDecoder().decode(encoded).also {
            require(it.size == 32) { "wpb.transaction-log.$label must decode to 32 bytes" }
        }
    }
}
