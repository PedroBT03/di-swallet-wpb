/**
 * Extracts human-readable claim previews from issued credential payloads.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * Parses SD-JWT disclosures or JWT payload claims for the issuance consent screen.
 */
@Component
class IssuedCredentialPreviewParser(
    private val sdJwtService: SdJwtService,
    private val mdocCredentialCodec: MdocCredentialCodec,
) {

    /**
     * Returns claim preview items for the given issued credential format.
     */
    fun parse(issued: IssuedCredential, deviceBound: Boolean): List<ClaimPreviewItem> = when (issued.format) {
        IssuanceCredentialFormat.SD_JWT_VC,
        IssuanceCredentialFormat.UNKNOWN,
        -> parseSdJwt(issued.rawPayload)
        IssuanceCredentialFormat.MSO_MDOC -> parseMdoc(issued.rawPayload)
    }

    /**
     * Decodes ISO 18013-5 mdoc issuer-signed bytes into human-readable claim names.
     */
    private fun parseMdoc(rawPayload: String): List<ClaimPreviewItem> {
        val document = mdocCredentialCodec.decode(rawPayload) ?: return emptyList()
        if (document.claims.isEmpty()) {
            return emptyList()
        }
        val namespacePrefix = "${document.namespace}."
        return document.claims.entries
            .sortedBy { it.key }
            .map { (qualifiedName, value) ->
                val name = when {
                    qualifiedName.startsWith(namespacePrefix) -> qualifiedName.removePrefix(namespacePrefix)
                    qualifiedName.contains('.') -> qualifiedName.substringAfterLast('.')
                    else -> qualifiedName
                }
                ClaimPreviewItem(
                    name = name,
                    value = formatPreviewValue(value),
                    previewAvailable = true,
                )
            }
    }

    /**
     * Prefers SD-JWT disclosures when present; otherwise falls back to the signed JWT payload.
     */
    private fun parseSdJwt(rawPayload: String): List<ClaimPreviewItem> {
        val parts = rawPayload.split('~').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()

        val decoded = parts.drop(1).mapNotNull { decodeDisclosure(it) }
        if (decoded.isNotEmpty()) {
            return buildPreviewFromDisclosures(decoded)
        }

        return decodeJwtPayloadClaims(parts.first())
    }

    /**
     * Builds qualified claim names for nested SD-JWT objects and omits `_sd` container values.
     */
    private fun buildPreviewFromDisclosures(
        disclosures: List<DecodedDisclosure>,
    ): List<ClaimPreviewItem> {
        val withDigests = disclosures.map { disclosure ->
            disclosure to sdJwtService.hashDisclosure(disclosure.raw)
        }
        val parentsByChildDigest = mutableMapOf<String, String>()
        withDigests.forEach { (disclosure, _) ->
            if (!isSdContainer(disclosure.value)) return@forEach
            sdDigestsInValue(disclosure.value).forEach { childDigest ->
                parentsByChildDigest[childDigest] = disclosure.name
            }
        }

        return withDigests
            .filter { (disclosure, _) -> !isSdContainer(disclosure.value) }
            .map { (disclosure, digest) ->
                val qualifiedName = parentsByChildDigest[digest]?.let { parent ->
                    "$parent.${disclosure.name}"
                } ?: disclosure.name
                ClaimPreviewItem(
                    name = qualifiedName,
                    value = formatPreviewValue(disclosure.value),
                    previewAvailable = true,
                )
            }
    }

    /**
     * Decodes a base64url SD-JWT disclosure array into claim metadata.
     */
    private fun decodeDisclosure(encoded: String): DecodedDisclosure? {
        return try {
            val json = String(Base64.getUrlDecoder().decode(encoded.trim()))
            val array = OBJECT_MAPPER.readValue(json, List::class.java)
            if (array.size >= 3 && array[1] is String) {
                DecodedDisclosure(
                    raw = encoded.trim(),
                    name = array[1] as String,
                    value = array[2],
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads non-standard JWT payload claims, skipping structural and metadata fields.
     */
    private fun decodeJwtPayloadClaims(signedJwt: String): List<ClaimPreviewItem> {
        val payloadSegment = signedJwt.split('.').getOrNull(1) ?: return emptyList()
        return try {
            val json = String(Base64.getUrlDecoder().decode(payloadSegment))
            val map = OBJECT_MAPPER.readValue(json, Map::class.java)
            map.filterKeys { key ->
                key is String && key !in SKIP_JWT_CLAIMS && !key.startsWith("_")
            }.map { (key, value) ->
                ClaimPreviewItem(
                    name = key as String,
                    value = formatPreviewValue(value),
                    previewAvailable = true,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isSdContainer(value: Any?): Boolean {
        val map = value as? Map<*, *> ?: return false
        return map.containsKey("_sd")
    }

    private fun sdDigestsInValue(value: Any?): Set<String> {
        val map = value as? Map<*, *> ?: return emptySet()
        val sd = map["_sd"] ?: return emptySet()
        val list = sd as? List<*> ?: return emptySet()
        return list.mapNotNull { it as? String }.toSet()
    }

    private fun formatPreviewValue(value: Any?): String? = when (value) {
        null -> null
        is List<*> -> value.joinToString(", ") { it?.toString() ?: "" }
        is Map<*, *> -> if (isSdContainer(value)) null else OBJECT_MAPPER.writeValueAsString(value)
        else -> value.toString()
    }

    private data class DecodedDisclosure(
        val raw: String,
        val name: String,
        val value: Any?,
    )

    companion object {
        private val OBJECT_MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
        private val SKIP_JWT_CLAIMS = setOf("iss", "sub", "aud", "exp", "iat", "nbf", "cnf", "_sd", "_sd_alg")
    }
}
