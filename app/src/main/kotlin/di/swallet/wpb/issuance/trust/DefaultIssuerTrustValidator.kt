package di.swallet.wpb.issuance.trust

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.IssuanceTrustDecision
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.springframework.stereotype.Component

/**
 * Baseline issuer trust validator for Phase 2.
 *
 * Enforces an allow-list of credential issuer identifiers and validates
 * `signed_metadata` according to [OpenId4VciProperties.sdk.metadataPolicy].
 * Federation, LoTE trust anchors and richer PKIX policies are deferred to Phase 5.
 */
@Component
class DefaultIssuerTrustValidator(
    private val properties: OpenId4VciProperties,
    private val signedMetadataValidator: IssuerSignedMetadataValidator,
) : IssuerTrustValidator {

    override fun validate(metadata: ResolvedIssuerMetadata): IssuanceTrustDecision {
        val allowed = properties.trust.allowedIssuerIds()
        if (allowed.isEmpty()) {
            if (properties.demoMode) {
                return applySignedMetadataPolicy(metadata, IssuanceTrustDecision(true, "demo-mode: empty allow-list bypassed"))
            }
            return IssuanceTrustDecision(false, "no allowed issuer identifiers configured")
        }

        val issuer = metadata.credentialIssuerId
        if (issuer.isBlank()) {
            return IssuanceTrustDecision(false, "issuer metadata missing credential_issuer identifier")
        }
        if (issuer !in allowed) {
            return IssuanceTrustDecision(false, "issuer '$issuer' not in allow-list")
        }
        return applySignedMetadataPolicy(metadata, IssuanceTrustDecision(true, null))
    }

    private fun applySignedMetadataPolicy(
        metadata: ResolvedIssuerMetadata,
        baseDecision: IssuanceTrustDecision,
    ): IssuanceTrustDecision {
        if (!baseDecision.trusted) return baseDecision
        val signed = signedMetadataValidator.validate(metadata)
        if (!signed.acceptable) {
            return IssuanceTrustDecision(false, signed.reason)
        }
        val reason = signed.reason ?: baseDecision.reason
        return IssuanceTrustDecision(true, reason)
    }
}
