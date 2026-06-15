package di.swallet.wpb.transactionlog

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.transactionlog.domain.Ts10Identifier
import org.springframework.stereotype.Component
import java.util.Base64

data class ResolvedCredentialIssuer(
    val name: String,
    val identifier: Ts10Identifier?,
)

@Component
class CredentialIssuerResolver(
    private val objectMapper: ObjectMapper,
) {
    fun resolve(credential: WalletCredential): ResolvedCredentialIssuer {
        val iss = extractIssClaim(credential.encodedData)
        if (!iss.isNullOrBlank()) {
            return ResolvedCredentialIssuer(
                name = iss,
                identifier = Ts10Identifier(
                    type = issuerIdentifierType(iss),
                    identifier = iss,
                ),
            )
        }
        val statusUri = credential.issuerStatusUri?.takeIf { it.isNotBlank() }
        if (statusUri != null) {
            return ResolvedCredentialIssuer(
                name = statusUri,
                identifier = Ts10Identifier(
                    type = issuerIdentifierType(statusUri),
                    identifier = statusUri,
                ),
            )
        }
        return ResolvedCredentialIssuer(
            name = "Wallet Provider",
            identifier = null,
        )
    }

    private fun extractIssClaim(encodedData: String): String? {
        if (encodedData.isBlank()) return null
        val signedJwt = encodedData.substringBefore('~').trim()
        val payloadSegment = signedJwt.split('.').getOrNull(1) ?: return null
        return try {
            val json = String(Base64.getUrlDecoder().decode(payloadSegment))
            val map = objectMapper.readValue(json, Map::class.java)
            (map["iss"] as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun issuerIdentifierType(value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) {
            "http://data.europa.eu/eudi/id/LEI"
        } else {
            "http://data.europa.eu/eudi/id/EUID"
        }
}
