/**
 * Orchestration port for the OID4VCI wallet issuance lifecycle.
 */

package di.swallet.wpb.issuance.orchestration

import di.swallet.wpb.consent.IssuanceConsentView
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.openid4vci.protocol.IssuanceConsentSubmission
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import java.util.UUID

/**
 * Orchestrator port for the OID4VCI issuance flow.
 *
 * Mirrors `PresentationFlowOrchestrator`: controllers stay thin
 * and delegate the lifecycle/state-machine handling to the orchestrator.
 */
interface IssuanceFlowOrchestrator {

    /** Parses a credential offer, validates trust and policy, and opens a session. */
    fun resolveOffer(offerUri: String, holderId: String?): IssuanceContext

    /** Prepares authorization with proof material and wallet instance attestation attached. */
    fun prepareAuthorization(sessionId: UUID, walletAttestationPopJwt: String? = null): IssuanceContext

    /** Exchanges an authorization code for tokens and validates WIA binding. */
    fun completeAuthorizationCode(sessionId: UUID, authorizationCode: String, state: String): IssuanceContext

    /** Completes a pre-authorized offer, optionally supplying a transaction code. */
    fun completePreAuthorizedCode(sessionId: UUID, txCode: String?, walletAttestationPopJwt: String? = null): IssuanceContext

    /** Requests credentials from the issuer, attaching key attestation when required. */
    fun requestCredential(sessionId: UUID, request: IssuanceRequest): IssuanceContext

    /** Polls the issuer for the outcome of a deferred credential request. */
    fun queryDeferred(sessionId: UUID): IssuanceContext

    /** Sends a post-issuance notification event to the credential issuer. */
    fun notify(sessionId: UUID, event: NotificationEvent, description: String?): IssuanceContext

    /** Builds the holder-facing view while credentials await storage consent. */
    fun getConsentView(sessionId: UUID, holderId: String): IssuanceConsentView

    /** Applies the holder's storage consent decision to staged credentials. */
    fun submitIssuanceConsent(sessionId: UUID, decision: IssuanceConsentSubmission): IssuanceContext

    /** Returns the current session, expiring stale consent-pending sessions when needed. */
    fun getSession(sessionId: UUID): IssuanceContext
}
