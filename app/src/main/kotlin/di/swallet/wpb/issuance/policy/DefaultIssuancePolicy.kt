package di.swallet.wpb.issuance.policy

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuancePolicyDecision
import org.springframework.stereotype.Component

/**
 * Default Phase 2 policy.
 *
 * - At least one credential configuration must be requested.
 * - All requested configurations must be advertised by the issuer
 *   (when issuer metadata has already been resolved).
 * - Only `SD_JWT_VC` configurations may be requested unless
 *   `wpb.openid4vci.policy.allow-mdoc` is enabled (defaults to false).
 */
@Component
class DefaultIssuancePolicy(
    private val properties: OpenId4VciProperties,
) : IssuancePolicy {

    override fun evaluate(context: IssuanceContext): IssuancePolicyDecision {
        val requestedIds = context.credentialConfigurationIds
        if (requestedIds.isEmpty()) {
            return IssuancePolicyDecision(false, "no credential configuration requested")
        }

        val metadata = context.issuerMetadata
        if (metadata != null && metadata.credentialConfigurations.isNotEmpty()) {
            val advertised = metadata.credentialConfigurations.associateBy { it.id }
            val unknown = requestedIds.filter { it !in advertised.keys }
            if (unknown.isNotEmpty()) {
                return IssuancePolicyDecision(false, "unsupported credential configurations: ${unknown.joinToString()}")
            }

            if (!properties.policy.allowMdoc) {
                val nonSdJwt = requestedIds
                    .mapNotNull { advertised[it] }
                    .filter { it.format != IssuanceCredentialFormat.SD_JWT_VC }
                if (nonSdJwt.isNotEmpty()) {
                    return IssuancePolicyDecision(
                        allowed = false,
                        reason = "non SD-JWT credential format requested (not allowed by policy): ${nonSdJwt.joinToString { it.id }}",
                    )
                }
            }
        }

        return IssuancePolicyDecision(true, null)
    }
}
