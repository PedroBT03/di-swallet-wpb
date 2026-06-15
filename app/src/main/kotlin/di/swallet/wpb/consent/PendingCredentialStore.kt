/**
 * Encrypts and decrypts pending issuance payloads while awaiting holder consent.
 */

package di.swallet.wpb.consent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.service.format.DisclosureCipherService
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Base64

/**
 * Pending issuance payload held encrypted until the holder approves or rejects storage.
 */
data class PendingIssuancePayload(
    val credentials: List<IssuedCredential>,
    val keyAliasHint: String?,
    val fromDeferred: Boolean,
    val expiresAt: Instant,
)

/**
 * Serializes pending credentials to encrypted storage and checks preview expiry.
 */
@Component
class PendingCredentialStore(
    private val disclosureCipher: DisclosureCipherService,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Serializes the payload to JSON and encrypts it for session storage.
     */
    fun encrypt(payload: PendingIssuancePayload): String {
        val json = objectMapper.writeValueAsString(payload)
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return disclosureCipher.encrypt(listOf(encoded))
    }

    /**
     * Decrypts and deserializes a previously stored pending issuance payload.
     */
    fun decrypt(encrypted: String): PendingIssuancePayload {
        val encoded = disclosureCipher.decrypt(encrypted).singleOrNull()
            ?: throw IllegalStateException("Pending credential payload is empty")
        val json = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        return objectMapper.readValue(json)
    }

    /**
     * Returns true when the preview TTL has elapsed.
     */
    fun isExpired(payload: PendingIssuancePayload, now: Instant = Instant.now()): Boolean =
        !now.isBefore(payload.expiresAt)
}
