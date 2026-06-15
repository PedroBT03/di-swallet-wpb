package di.swallet.wpb.security

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64

object Fido2ChallengeSupport {
    fun extractChallengeKey(clientDataJsonBase64Url: String, objectMapper: ObjectMapper): String {
        val json = String(Base64.getUrlDecoder().decode(clientDataJsonBase64Url))
        val challenge = objectMapper.readTree(json).get("challenge")?.asText()
        require(!challenge.isNullOrBlank()) { "FIDO2 clientDataJSON is missing challenge" }
        return challenge
    }
}
