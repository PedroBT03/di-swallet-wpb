package di.swallet.wpb.presentation.orchestration

import di.swallet.wpb.consent.PresentationConsentView
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.presentation.domain.PresentationContext
import java.util.UUID

interface PresentationFlowOrchestrator {
    suspend fun startSession(requestUri: String, holderId: String? = null): PresentationContext

    suspend fun getConsentView(sessionId: UUID, holderId: String): PresentationConsentView

    suspend fun submitConsent(sessionId: UUID, decision: ConsentSubmission): PresentationContext

    suspend fun getSession(sessionId: UUID): PresentationContext
}
