package di.swallet.wpb.issuance.trust

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.IssuanceTrustDecision
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.springframework.stereotype.Component

/**
 * Baseline issuer trust validator for Phase 2.
 *
 * The MVP enforces an allow-list of credential issuer identifiers; signed
 * metadata verification, X.509 trust chains and richer metadata policies
 * (federation, EUDI Trust List) are deferred to a later phase.
 *
 * When the allow-list is empty and `demo-mode=true`, all metadata is
 * accepted. When `demo-mode=false`, an empty allow-list rejects every
 * issuer (fail-closed for production-like profiles).
 */
@Component
class DefaultIssuerTrustValidator(
    private val properties: OpenId4VciProperties,
) : IssuerTrustValidator {

    override fun validate(metadata: ResolvedIssuerMetadata): IssuanceTrustDecision {
        val allowed = properties.trust.allowedIssuerIds()
        if (allowed.isEmpty()) {
            return if (properties.demoMode) {
                IssuanceTrustDecision(true, "demo-mode: empty allow-list bypassed")
            } else {
                IssuanceTrustDecision(false, "no allowed issuer identifiers configured")
            }
        }

        val issuer = metadata.credentialIssuerId
        if (issuer.isBlank()) {
            return IssuanceTrustDecision(false, "issuer metadata missing credential_issuer identifier")
        }
        if (issuer !in allowed) {
            return IssuanceTrustDecision(false, "issuer '$issuer' not in allow-list")
        }
        return IssuanceTrustDecision(true, null)
    }
}
