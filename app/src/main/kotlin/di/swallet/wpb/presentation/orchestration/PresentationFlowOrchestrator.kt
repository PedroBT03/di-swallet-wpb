/**
 * Coordinates the OpenID4VP presentation session lifecycle.
 */

package di.swallet.wpb.presentation.orchestration

import di.swallet.wpb.consent.PresentationConsentView
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.presentation.domain.PresentationContext
import java.util.UUID

/**
 * Entry point for starting, consenting to, and querying presentation sessions.
 */
interface PresentationFlowOrchestrator {
    /**
     * Resolves a verifier request URI and runs trust, registry, matching, and policy checks.
     */
    suspend fun startSession(requestUri: String, holderId: String? = null): PresentationContext

    /**
     * Builds the holder-facing consent screen for a session awaiting approval.
     */
    suspend fun getConsentView(sessionId: UUID, holderId: String): PresentationConsentView

    /**
     * Applies the holder consent decision and, when granted, builds and dispatches the VP.
     */
    suspend fun submitConsent(sessionId: UUID, decision: ConsentSubmission): PresentationContext

    /**
     * Loads the current presentation context, expiring the session when its TTL has passed.
     */
    suspend fun getSession(sessionId: UUID): PresentationContext
}
