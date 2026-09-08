/**
 * Default wallet policy for OID4VCI credential configuration selection.
 */

package di.swallet.wpb.issuance.policy

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuancePolicyDecision
import org.springframework.stereotype.Component

/**
 * Default issuance policy.
 *
 * - At least one credential configuration must be requested.
 * - All requested configurations must be advertised by the issuer
 *   (when issuer metadata has already been resolved).
 * - SD-JWT VC and mdoc (`MSO_MDOC`) configurations are both accepted.
 */
@Component
class DefaultIssuancePolicy : IssuancePolicy {

    /** Denies empty requests and configurations not advertised by the issuer. */
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
        }

        return IssuancePolicyDecision(true, null)
    }
}
