/**
 * Maps DCQL credential type hints to stored wallet credential types.
 */

package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.CredentialTypeLabels
import java.util.Base64

/**
 * Best-effort equivalence between DCQL `vct_values` / `doctype_values` hints and
 * wallet `credentialType` labels (including OID4VCI configuration ids).
 */
object CredentialTypeHintMatcher {

    /** Returns true when [hints] are empty or any hint matches [credentialType] or SD-JWT `vct`. */
    fun sdJwtTypeMatches(
        hints: List<String>,
        credentialType: String,
        issuerJwt: String? = null,
    ): Boolean {
        if (hints.isEmpty()) return true
        val types = linkedSetOf(credentialType)
        extractVct(issuerJwt)?.let { types.add(it) }
        return hints.any { hint -> types.any { type -> equivalent(hint, type) } }
    }

    /** Returns true when [hints] are empty or any hint matches [docType]. */
    fun mdocTypeMatches(hints: List<String>, docType: String): Boolean {
        if (hints.isEmpty()) return true
        return hints.any { hint -> equivalent(hint, docType) || mdlHintMatchesStoredType(hint, docType) }
    }

    private fun mdlHintMatchesStoredType(hint: String, docType: String): Boolean {
        if (!isMdlHint(hint)) return false
        return CredentialTypeLabels.isMdlType(docType) ||
            docType.equals("org.iso.18013.5.1.mDL", ignoreCase = true)
    }

    private fun isMdlHint(hint: String): Boolean {
        val normalized = hint.lowercase()
        return normalized.contains("18013") || normalized.contains("mdl")
    }

    private fun equivalent(hint: String, type: String): Boolean {
        if (hint.equals(type, ignoreCase = true)) return true
        if (isPidHint(hint) && isPidCredential(type)) return true
        return false
    }

    private fun isPidHint(hint: String): Boolean =
        hint.equals("PID", ignoreCase = true)

    private fun isPidCredential(type: String): Boolean = CredentialTypeLabels.isPidType(type)

    private fun extractVct(issuerJwt: String?): String? {
        val payloadB64 = issuerJwt?.split('.')?.getOrNull(1) ?: return null
        return try {
            val json = String(Base64.getUrlDecoder().decode(payloadB64))
            Regex(""""vct"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        } catch (_: Throwable) {
            null
        }
    }
}
