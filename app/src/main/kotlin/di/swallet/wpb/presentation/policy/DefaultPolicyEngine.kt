package di.swallet.wpb.presentation.policy

import di.swallet.wpb.presentation.domain.PolicyDecision
import di.swallet.wpb.presentation.domain.PresentationContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Policy engine.
 *
 *  - Requires the verifier to have passed trust validation.
 *  - Requires at least one matching candidate, or `demo-mode=true` if there
 *    are no matches (so the verifier emulator can exercise the dispatch path
 *    without any real credentials in the DB).
 *  - Enforces an explicit allow-list of supported response modes to avoid
 *    silently accepting modes the orchestrator does not exercise.
 *
 *  Production-grade policy (RP intended use, attribute minimisation, consent
 *  policy, etc.) is TODO.
 */
@Service
class DefaultPolicyEngine(
    @param:Value("\${wpb.openid4vp.demo-mode:false}") private val demoMode: Boolean,
) : PolicyEngine {

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

        if (context.credentialCandidates.isEmpty()) {
            return context.copy(
                policyDecision = PolicyDecision(
                    allowed = demoMode,
                    reason = if (demoMode) {
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
}
