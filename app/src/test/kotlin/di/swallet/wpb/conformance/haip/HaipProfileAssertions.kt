/**
 * Asserts HAIP profile requirements on resolved authorization requests and VP tokens.
 */

package di.swallet.wpb.conformance.haip

import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.VpToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Explicit HAIP-profile checks for OIA_03b/c (SD-JWT VP) and ISSU_01 (OID4VCI path exercised elsewhere).
 * Scope is intentionally narrow - not a full ARB/HAIP certification checklist.
 */
object HaipProfileAssertions {

    /** Asserts direct_post response mode, non-blank nonce, response URI, and at least one DCQL credential query id. */
    fun assertOpenId4VpHaipRequestProfile(request: ResolvedAuthorizationRequest) {
        assertEquals(PresentationResponseMode.DIRECT_POST, request.responseMode)
        assertNotNull(request.nonce)
        assertFalse(request.nonce.isBlank())
        assertNotNull(request.responseUri)
        assertTrue(request.requirements.credentialQueryIds.isNotEmpty())
    }

    /** Asserts every parsed credential query uses SD-JWT format and carries a non-blank query id. */
    fun assertDcqlSdJwtProfile(request: ResolvedAuthorizationRequest) {
        val queries = request.requirements.credentialQueries
        assertFalse(queries.isEmpty(), "DCQL credentials[] must be present for HAIP SD-JWT VP")
        queries.forEach { query ->
            assertEquals(CredentialFormat.SD_JWT, query.format)
            assertFalse(query.id.isBlank())
        }
    }

    /** Asserts each consent candidate's requested claim paths match the corresponding DCQL query when paths are declared. */
    fun assertConsentCandidateClaimsSubset(context: PresentationContext) {
        val queries = context.presentationRequirements?.credentialQueries.orEmpty()
        if (queries.isEmpty()) return
        context.credentialCandidates.forEach { candidate ->
            val query = queries.firstOrNull { it.id == candidate.queryId }
            if (query != null && query.requestedClaimPaths.isNotEmpty()) {
                assertEquals(query.requestedClaimPaths, candidate.requestedClaimPaths)
            }
        }
    }

    /** Asserts the VP token is SD-JWT with issuer JWT and KB-JWT segments separated by tilde delimiters. */
    fun assertSdJwtVpPresentationToken(vpToken: VpToken) {
        assertEquals(CredentialFormat.SD_JWT, vpToken.format)
        val presentation = vpToken.presentationsByQueryId.values.flatten().firstOrNull()
        assertNotNull(presentation, "VP token must contain at least one SD-JWT presentation")
        val parts = presentation!!.split('~')
        assertTrue(parts.size >= 2, "SD-JWT VP must contain issuer JWT and KB-JWT segments")
        val kbJwt = parts.last()
        assertFalse(kbJwt.isBlank(), "KB-JWT segment must be present (HAIP SD-JWT VP)")
    }
}
