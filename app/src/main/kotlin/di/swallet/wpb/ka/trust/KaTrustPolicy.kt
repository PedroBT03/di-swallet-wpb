/**
 * Effective key attestation trust mode resolution from configuration.
 */

package di.swallet.wpb.ka.trust

import di.swallet.wpb.config.OpenId4VciProperties

enum class KaTrustMode {
    RELAXED,
    STRICT,
}

/** Resolves whether KA x5c validation runs in relaxed or strict PKIX mode. */
object KaTrustPolicy {
    /**
     * Resolves the effective KA trust mode.
     *
     * Outside demo-mode, `relaxed` is upgraded to [KaTrustMode.STRICT] when
     * [OpenId4VciProperties.KaProperties.enforceProductionTrustPolicy] is enabled
     * and no fingerprint or trust-anchor mitigations are configured.
     */
    fun effectiveMode(properties: OpenId4VciProperties): KaTrustMode {
        val configured = properties.ka.trustMode.trim().lowercase()
        if (configured == "strict") {
            return KaTrustMode.STRICT
        }
        if (!properties.demoMode && properties.ka.enforceProductionTrustPolicy) {
            val hasMitigation = properties.ka.allowedX5cFingerprints().isNotEmpty() ||
                properties.ka.trustAnchorPemPaths().isNotEmpty()
            if (!hasMitigation) {
                return KaTrustMode.STRICT
            }
        }
        return KaTrustMode.RELAXED
    }
}
