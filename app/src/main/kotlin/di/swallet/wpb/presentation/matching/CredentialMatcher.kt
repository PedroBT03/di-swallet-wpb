/**
 * Matches wallet credentials against verifier DCQL queries.
 */

package di.swallet.wpb.presentation.matching

import di.swallet.wpb.presentation.domain.PresentationContext

/**
 * Finds wallet credentials that satisfy the verifier's presentation request.
 */
interface CredentialMatcher {
    /**
     * Populates credential candidates on the context from stored wallet credentials.
     */
    fun match(context: PresentationContext): PresentationContext

    /**
     * Returns true when the wallet holds revoked credentials that would match the verifier request.
     */
    fun hasRevokedMatches(context: PresentationContext): Boolean
}
