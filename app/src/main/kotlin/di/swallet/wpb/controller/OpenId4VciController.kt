/**
 * REST endpoints for the OpenID4VCI credential issuance session lifecycle.
 */

package di.swallet.wpb.controller

import di.swallet.wpb.consent.IssuanceConsentView
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.orchestration.IssuanceFlowOrchestrator
import di.swallet.wpb.observability.IssuanceEvent
import di.swallet.wpb.observability.IssuanceEventStore
import di.swallet.wpb.openid4vci.protocol.IssuanceConsentSubmission
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.security.AuthenticatedHolderGuard
import di.swallet.wpb.security.Oid4SessionAccessGuard
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Thin REST controller for the OID4VCI issuance lifecycle.
 *
 * Mirrors `OpenId4VpController` from Phase 1: only routing, payload binding
 * and serialisation; all business logic lives in
 * [IssuanceFlowOrchestrator].
 */
@RestController
@RequestMapping("/openid4vci")
@Tag(name = "OpenID4VCI", description = "OID4VCI issuance lifecycle endpoints")
class OpenId4VciController(
    private val orchestrator: IssuanceFlowOrchestrator,
    private val eventStore: IssuanceEventStore,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
    private val oid4SessionAccessGuard: Oid4SessionAccessGuard,
) {

    /**
     * Resolves a credential offer URI and starts an issuance session.
     */
    @PostMapping("/offer/resolve")
    @Operation(summary = "Resolve a credential offer (by-value or by-reference)")
    fun resolveOffer(@RequestBody request: OfferResolveRequest): IssuanceContext =
        orchestrator.resolveOffer(request.offerUri, request.holderId)

    /**
     * Prepares an authorization-code grant with PKCE, PAR, or DPoP as supported.
     */
    @PostMapping("/authorize/prepare")
    @Operation(summary = "Prepare an authorization-code grant (PKCE / PAR / DPoP)")
    fun prepareAuthorization(@RequestBody request: SessionScopedRequest): IssuanceContext =
        orchestrator.prepareAuthorization(
            UUID.fromString(request.sessionId),
            request.walletAttestationPopJwt,
        )

    /**
     * Exchanges an authorization code for tokens and advances the issuance session.
     */
    @PostMapping("/authorize/code")
    @Operation(summary = "Complete an authorization code grant")
    fun completeAuthorizationCode(@RequestBody request: AuthorizationCodeRequest): IssuanceContext =
        orchestrator.completeAuthorizationCode(
            sessionId = UUID.fromString(request.sessionId),
            authorizationCode = request.authorizationCode,
            state = request.state,
        )

    /**
     * Completes a pre-authorized code grant, optionally with a transaction code.
     */
    @PostMapping("/authorize/pre-authorized")
    @Operation(summary = "Complete a pre-authorized code grant (with optional tx_code)")
    fun completePreAuthorized(@RequestBody request: PreAuthorizedRequest): IssuanceContext =
        orchestrator.completePreAuthorizedCode(
            sessionId = UUID.fromString(request.sessionId),
            txCode = request.txCode,
            walletAttestationPopJwt = request.walletAttestationPopJwt,
        )

    /**
     * Requests credential issuance using a configuration id or credential identifier.
     */
    @PostMapping("/credential/request")
    @Operation(summary = "Request a credential (configuration or identifier-based)")
    fun requestCredential(@RequestBody request: CredentialRequest): IssuanceContext =
        orchestrator.requestCredential(
            sessionId = UUID.fromString(request.sessionId),
            request = IssuanceRequest(
                credentialConfigurationId = request.credentialConfigurationId,
                credentialIdentifier = request.credentialIdentifier,
                claims = request.claims,
            ),
        )

    /**
     * Polls the issuer when credential delivery was deferred.
     */
    @PostMapping("/deferred/query")
    @Operation(summary = "Poll the issuer for a deferred credential")
    fun queryDeferred(@RequestBody request: SessionScopedRequest): IssuanceContext =
        orchestrator.queryDeferred(UUID.fromString(request.sessionId))

    /**
     * Notifies the issuer about credential storage success or failure on the wallet side.
     */
    @PostMapping("/notify")
    @Operation(summary = "Notify the issuer about credential storage status")
    fun notify(@RequestBody request: NotifyRequest): IssuanceContext =
        orchestrator.notify(
            sessionId = UUID.fromString(request.sessionId),
            event = request.event,
            description = request.description,
        )

    /**
     * Returns the holder consent preview before credentials are stored in the wallet.
     */
    @GetMapping("/session/{id}/consent-view")
    @Operation(
        summary = "Get issuance storage consent preview for WPI",
        description = "Shows issuer identity and claim preview before ISSU_11 storage approval.",
    )
    fun getConsentView(
        @PathVariable id: UUID,
        @RequestParam holderId: String,
    ): IssuanceConsentView {
        oid4SessionAccessGuard.requireSessionHolder(holderId)
        return orchestrator.getConsentView(id, holderId)
    }

    /**
     * Submits holder approval or rejection for credential storage.
     */
    @PostMapping("/consent")
    @Operation(summary = "Approve or reject credential storage (requires FIDO2)")
    fun submitConsent(@RequestBody request: IssuanceConsentSubmission): IssuanceContext {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return orchestrator.submitIssuanceConsent(UUID.fromString(request.sessionId), request)
    }

    /**
     * Returns the low-level issuance session context for debugging or advanced clients.
     */
    @GetMapping("/session/{id}")
    @Operation(
        summary = "Get an issuance session",
        description = "Low-level lifecycle context. WPI storage consent UI should use GET /session/{id}/consent-view.",
    )
    fun getSession(@PathVariable id: UUID): IssuanceContext {
        val session = orchestrator.getSession(id)
        oid4SessionAccessGuard.requireSessionHolder(session.sessionMeta.holderId)
        return session
    }

    /**
     * Returns the audit event timeline for an issuance session.
     */
    @GetMapping("/session/{id}/events")
    @Operation(summary = "Get issuance session events")
    fun getSessionEvents(@PathVariable id: UUID): List<IssuanceEvent> {
        val session = orchestrator.getSession(id)
        oid4SessionAccessGuard.requireSessionHolder(session.sessionMeta.holderId)
        return eventStore.getEvents(id)
    }
}

/** Request body for resolving a credential offer URI. */
data class OfferResolveRequest(val offerUri: String, val holderId: String? = null)

/** Request body that identifies an issuance session by id. */
data class SessionScopedRequest(
    val sessionId: String,
    val walletAttestationPopJwt: String? = null,
)

/** Request body for completing an authorization-code grant. */
data class AuthorizationCodeRequest(val sessionId: String, val authorizationCode: String, val state: String)

/** Request body for completing a pre-authorized code grant. */
data class PreAuthorizedRequest(
    val sessionId: String,
    val txCode: String? = null,
    val walletAttestationPopJwt: String? = null,
)

/** Request body for requesting credential issuance from the issuer. */
data class CredentialRequest(
    val sessionId: String,
    val credentialConfigurationId: String? = null,
    val credentialIdentifier: String? = null,
    val claims: List<String> = emptyList(),
)

/** Request body for notifying the issuer about wallet-side storage outcome. */
data class NotifyRequest(val sessionId: String, val event: NotificationEvent, val description: String? = null)
