/**
 * Shared test helpers for issuance consent.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.orchestration.IssuanceFlowOrchestrator
import di.swallet.wpb.openid4vci.protocol.IssuanceConsentSubmission

object IssuanceConsentTestSupport {

    /**
     * If issuance consent is pending, submits a granted consent decision for the holder;
     * otherwise returns the context unchanged.
     */
    fun approveStorageIfPending(
        orchestrator: IssuanceFlowOrchestrator,
        ctx: IssuanceContext,
        holderId: String,
    ): IssuanceContext {
        if (ctx.state != IssuanceState.ISSUANCE_CONSENT_PENDING) return ctx
        return orchestrator.submitIssuanceConsent(
            ctx.sessionMeta.sessionId,
            IssuanceConsentSubmission(
                sessionId = ctx.sessionMeta.sessionId.toString(),
                holderId = holderId,
                granted = true,
            ),
        )
    }
}
