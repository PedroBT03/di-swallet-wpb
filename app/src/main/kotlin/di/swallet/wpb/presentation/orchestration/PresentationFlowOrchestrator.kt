package di.swallet.wpb.presentation.orchestration

import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.presentation.domain.PresentationContext
import java.util.UUID

interface PresentationFlowOrchestrator {
    suspend fun startSession(requestUri: String, holderId: String? = null): PresentationContext

    suspend fun submitConsent(sessionId: UUID, decision: ConsentSubmission): PresentationContext

    suspend fun getSession(sessionId: UUID): PresentationContext
}
