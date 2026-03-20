package di.swallet.wpb.service.format

import di.swallet.wpb.config.WalletProperties
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts SD-JWT disclosures at rest to reduce exposure of plaintext attributes in database storage.
 */
@Service
class DisclosureCipherService(
    walletProperties: WalletProperties
) {

    private val secureRandom = SecureRandom()
    private val keyBytes: ByteArray = Base64.getDecoder().decode(walletProperties.disclosures.encryptionKey).also {
        require(it.size == 32) { "wallet.disclosures.encryption-key must decode to 32 bytes" }
    }

    fun encrypt(disclosures: List<String>): String {
        val plaintext = disclosures.joinToString("~")
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encryptedDisclosures: String): List<String> {
        if (encryptedDisclosures.isBlank()) return emptyList()

        val payload = Base64.getDecoder().decode(encryptedDisclosures)
        require(payload.size > 12) { "Encrypted disclosures payload is invalid" }

        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))

        val plaintext = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        if (plaintext.isBlank()) return emptyList()
        return plaintext.split("~").filter { it.isNotBlank() }
    }
}