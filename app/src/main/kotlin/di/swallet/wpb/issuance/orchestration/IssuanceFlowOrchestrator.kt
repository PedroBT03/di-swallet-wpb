package di.swallet.wpb.issuance.orchestration

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import java.util.UUID

/**
 * Orchestrator port for the OID4VCI issuance flow.
 *
 * Mirrors `PresentationFlowOrchestrator` from Phase 1: controllers stay thin
 * and delegate the lifecycle/state-machine handling to the orchestrator.
 */
interface IssuanceFlowOrchestrator {

    fun resolveOffer(offerUri: String, holderId: String?): IssuanceContext

    fun prepareAuthorization(sessionId: UUID): IssuanceContext

    fun completeAuthorizationCode(sessionId: UUID, authorizationCode: String, state: String): IssuanceContext

    fun completePreAuthorizedCode(sessionId: UUID, txCode: String?): IssuanceContext

    fun requestCredential(sessionId: UUID, request: IssuanceRequest): IssuanceContext

    fun queryDeferred(sessionId: UUID): IssuanceContext

    fun notify(sessionId: UUID, event: NotificationEvent, description: String?): IssuanceContext

    fun getSession(sessionId: UUID): IssuanceContext
}
