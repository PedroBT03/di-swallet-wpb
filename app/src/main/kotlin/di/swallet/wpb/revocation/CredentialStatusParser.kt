package di.swallet.wpb.revocation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component

/**
 * Best-effort extraction of credential status references from issued payloads.
 */
@Component
class CredentialStatusParser(
    private val objectMapper: ObjectMapper,
) {
    fun parseFromSdJwt(raw: String): CredentialStatusReference? {
        val issuerJwt = raw.substringBefore('~').trim()
        if (issuerJwt.isBlank()) return null
        return runCatching {
            val claims = SignedJWT.parse(issuerJwt).jwtClaimsSet.toJSONObject()
            parseFromClaimsMap(claims)
        }.getOrNull()
    }

    fun parseFromClaimsMap(claims: Map<*, *>): CredentialStatusReference? {
        val node = objectMapper.valueToTree<JsonNode>(claims)
        return parseFromJsonNode(node)
    }

    private fun parseFromJsonNode(root: JsonNode): CredentialStatusReference? {
        val statusNode = root.path("credentialStatus").takeIf { !it.isMissingNode }
            ?: root.path("status").takeIf { !it.isMissingNode }
            ?: return null

        val listCredential = textOrNull(statusNode, "statusListCredential")
            ?: textOrNull(statusNode, "status_list_credential")
        val index = when {
            statusNode.has("statusListIndex") -> statusNode.path("statusListIndex").asInt()
            statusNode.has("status_list_index") -> statusNode.path("status_list_index").asInt()
            else -> null
        }
        val id = textOrNull(statusNode, "id")
        val uri = listCredential ?: id?.substringBefore('#')

        if (uri.isNullOrBlank() && index == null) return null
        return CredentialStatusReference(listUri = uri, listIndex = index, managedByWalletProvider = false)
    }

    private fun textOrNull(node: JsonNode, field: String): String? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return null
        val text = value.asText()
        return text.takeIf { it.isNotBlank() }
    }
}
