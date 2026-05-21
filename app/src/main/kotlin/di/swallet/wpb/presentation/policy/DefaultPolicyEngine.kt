package di.swallet.wpb.presentation.policy

import di.swallet.wpb.presentation.domain.PolicyDecision
import di.swallet.wpb.presentation.domain.PresentationContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

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

        val hasCandidates = context.credentialCandidates.isNotEmpty()
        if (!hasCandidates) {
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
