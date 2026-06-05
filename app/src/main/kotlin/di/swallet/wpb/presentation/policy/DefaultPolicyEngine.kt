package di.swallet.wpb.presentation.policy

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.presentation.domain.PolicyDecision
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.registry.RegistryIntendedUseMatcher
import org.springframework.stereotype.Service

/**
 * Policy engine for OpenID4VP presentation flows.
 *
 * Enforces:
 *  - verifier trust acceptance;
 *  - TS5/TS6 registry data when registry validation is enabled;
 *  - supported response modes exercised by the orchestrator;
 *  - at least one credential candidate (or demo-mode synthetic continuation).
 */
@Service
class DefaultPolicyEngine(
    private val properties: OpenId4VpProperties,
) : PolicyEngine {

    private val supportedResponseModes: Set<PresentationResponseMode> = setOf(
        PresentationResponseMode.DIRECT_POST,
        PresentationResponseMode.DIRECT_POST_JWT,
    )

    override fun evaluate(context: PresentationContext): PresentationContext {
        val trusted = context.trustDecision?.trusted == true
        if (!trusted) {
            return context.copy(
                policyDecision = PolicyDecision(
                    allowed = false,
                    reason = context.trustDecision?.reason ?: "Verifier failed trust validation",
                ),
            )
        }

        val registryRejection = evaluateRegistryPolicy(context)
        if (registryRejection != null) {
            return context.copy(policyDecision = registryRejection)
        }

        val request = context.authorizationRequest
        val responseMode = request?.responseMode
        if (responseMode != null && responseMode !in supportedResponseModes) {
            return context.copy(
                policyDecision = PolicyDecision(
                    allowed = false,
                    reason = "Unsupported response mode '${responseMode.wireValue()}'",
                ),
            )
        }

        if (context.credentialCandidates.isEmpty()) {
            return context.copy(
                policyDecision = PolicyDecision(
                    allowed = properties.demoMode,
                    reason = if (properties.demoMode) {
                        "Demo mode: no wallet credentials matched, continuing with synthetic candidates"
                    } else {
                        "No wallet credentials matched the verifier request"
                    },
                ),
            )
        }

        return context.copy(
            policyDecision = PolicyDecision(
                allowed = true,
                reason = "Policy accepted",
            ),
        )
    }

    private fun evaluateRegistryPolicy(context: PresentationContext): PolicyDecision? {
        if (!properties.registry.enabled) return null

        val decision = context.registryDecision
        if (decision?.accepted != true) {
            return PolicyDecision(
                allowed = false,
                reason = decision?.reason ?: "Registry validation required but not accepted",
            )
        }

        val record = context.registryRecord
            ?: return PolicyDecision(
                allowed = false,
                reason = "Registry record missing after accepted registry validation",
            )

        val queries = context.presentationRequirements?.credentialQueries.orEmpty()
        if (queries.isNotEmpty()) {
            if (!decision.intendedUseChecked) {
                return PolicyDecision(
                    allowed = false,
                    reason = "Registry intended-use check was not completed",
                )
            }
            if (!RegistryIntendedUseMatcher.coversQueries(record, queries)) {
                return PolicyDecision(
                    allowed = false,
                    reason = "Requested credentials exceed registry registered intended use",
                )
            }
        }

        if (properties.registry.requirePrivacyPolicyUri && !RegistryIntendedUseMatcher.hasPrivacyPolicyUri(record)) {
            return PolicyDecision(
                allowed = false,
                reason = "Registry record lacks privacy policy URI for registered intended use",
            )
        }

        return null
    }
}
