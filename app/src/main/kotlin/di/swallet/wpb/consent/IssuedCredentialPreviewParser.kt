package di.swallet.wpb.consent

import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import org.springframework.stereotype.Component
import java.util.Base64

@Component
class IssuedCredentialPreviewParser {

    fun parse(issued: IssuedCredential, deviceBound: Boolean): List<ClaimPreviewItem> = when (issued.format) {
        IssuanceCredentialFormat.SD_JWT_VC,
        IssuanceCredentialFormat.UNKNOWN,
        -> parseSdJwt(issued.rawPayload)
        IssuanceCredentialFormat.MSO_MDOC -> listOf(
            ClaimPreviewItem(
                name = "claims_preview_unavailable",
                value = null,
                previewAvailable = false,
            ),
        )
    }

    private fun parseSdJwt(rawPayload: String): List<ClaimPreviewItem> {
        val parts = rawPayload.split('~').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()

        val disclosures = parts.drop(1).mapNotNull { decodeDisclosure(it) }
        if (disclosures.isNotEmpty()) {
            return disclosures.map { (name, value) ->
                ClaimPreviewItem(name = name, value = value?.toString(), previewAvailable = true)
            }
        }

        return decodeJwtPayloadClaims(parts.first())
    }

    private fun decodeDisclosure(encoded: String): Pair<String, Any?>? {
        return try {
            val json = String(Base64.getUrlDecoder().decode(encoded.trim()))
            val array = OBJECT_MAPPER.readValue(json, List::class.java)
            if (array.size >= 3 && array[1] is String) {
                array[1] as String to array[2]
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeJwtPayloadClaims(signedJwt: String): List<ClaimPreviewItem> {
        val payloadSegment = signedJwt.split('.').getOrNull(1) ?: return emptyList()
        return try {
            val json = String(Base64.getUrlDecoder().decode(payloadSegment))
            val map = OBJECT_MAPPER.readValue(json, Map::class.java)
            map.filterKeys { key ->
                key is String && key !in SKIP_JWT_CLAIMS && !key.startsWith("_")
            }.map { (key, value) ->
                ClaimPreviewItem(name = key as String, value = value?.toString(), previewAvailable = true)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val OBJECT_MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
        private val SKIP_JWT_CLAIMS = setOf("iss", "sub", "aud", "exp", "iat", "nbf", "cnf", "_sd", "_sd_alg")
    }
}
