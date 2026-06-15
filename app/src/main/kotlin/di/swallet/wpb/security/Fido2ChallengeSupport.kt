/**
 * Helpers for extracting WebAuthn challenge values from client data JSON.
 */

package di.swallet.wpb.security

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Parses FIDO2 clientDataJSON to obtain the challenge key used for lookup.
 */
object Fido2ChallengeSupport {
    /**
     * Decodes clientDataJSON and returns the embedded WebAuthn challenge string.
     */
    fun extractChallengeKey(clientDataJsonBase64Url: String, objectMapper: ObjectMapper): String {
        val json = String(Base64.getUrlDecoder().decode(clientDataJsonBase64Url))
        val challenge = objectMapper.readTree(json).get("challenge")?.asText()
        require(!challenge.isNullOrBlank()) { "FIDO2 clientDataJSON is missing challenge" }
        return challenge
    }
}
