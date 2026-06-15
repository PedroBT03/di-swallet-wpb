/**
 * Issuer trust decision applied after credential issuer metadata is resolved.
 */

package di.swallet.wpb.issuance.trust

import di.swallet.wpb.issuance.domain.IssuanceTrustDecision
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata

/** Decides whether resolved issuer metadata is trusted for issuance. */
interface IssuerTrustValidator {
    /** Returns a trusted/denied decision with an optional human-readable reason. */
    fun validate(metadata: ResolvedIssuerMetadata): IssuanceTrustDecision
}
