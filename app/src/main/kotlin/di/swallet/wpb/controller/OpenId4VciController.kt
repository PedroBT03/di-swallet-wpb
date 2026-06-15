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
@Tag(name = "OpenID4VCI", description = "OID4VCI / Phase 2 issuance lifecycle endpoints")
class OpenId4VciController(
    private val orchestrator: IssuanceFlowOrchestrator,
    private val eventStore: IssuanceEventStore,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
    private val oid4SessionAccessGuard: Oid4SessionAccessGuard,
) {

    @PostMapping("/offer/resolve")
    @Operation(summary = "Resolve a credential offer (by-value or by-reference)")
    fun resolveOffer(@RequestBody request: OfferResolveRequest): IssuanceContext =
        orchestrator.resolveOffer(request.offerUri, request.holderId)

    @PostMapping("/authorize/prepare")
    @Operation(summary = "Prepare an authorization-code grant (PKCE / PAR / DPoP)")
    fun prepareAuthorization(@RequestBody request: SessionScopedRequest): IssuanceContext =
        orchestrator.prepareAuthorization(UUID.fromString(request.sessionId))

    @PostMapping("/authorize/code")
    @Operation(summary = "Complete an authorization code grant")
    fun completeAuthorizationCode(@RequestBody request: AuthorizationCodeRequest): IssuanceContext =
        orchestrator.completeAuthorizationCode(
            sessionId = UUID.fromString(request.sessionId),
            authorizationCode = request.authorizationCode,
            state = request.state,
        )

    @PostMapping("/authorize/pre-authorized")
    @Operation(summary = "Complete a pre-authorized code grant (with optional tx_code)")
    fun completePreAuthorized(@RequestBody request: PreAuthorizedRequest): IssuanceContext =
        orchestrator.completePreAuthorizedCode(
            sessionId = UUID.fromString(request.sessionId),
            txCode = request.txCode,
        )

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

    @PostMapping("/deferred/query")
    @Operation(summary = "Poll the issuer for a deferred credential")
    fun queryDeferred(@RequestBody request: SessionScopedRequest): IssuanceContext =
        orchestrator.queryDeferred(UUID.fromString(request.sessionId))

    @PostMapping("/notify")
    @Operation(summary = "Notify the issuer about credential storage status")
    fun notify(@RequestBody request: NotifyRequest): IssuanceContext =
        orchestrator.notify(
            sessionId = UUID.fromString(request.sessionId),
            event = request.event,
            description = request.description,
        )

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

    @PostMapping("/consent")
    @Operation(summary = "Approve or reject credential storage (requires FIDO2)")
    fun submitConsent(@RequestBody request: IssuanceConsentSubmission): IssuanceContext {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return orchestrator.submitIssuanceConsent(UUID.fromString(request.sessionId), request)
    }

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

    @GetMapping("/session/{id}/events")
    @Operation(summary = "Get issuance session events")
    fun getSessionEvents(@PathVariable id: UUID): List<IssuanceEvent> {
        val session = orchestrator.getSession(id)
        oid4SessionAccessGuard.requireSessionHolder(session.sessionMeta.holderId)
        return eventStore.getEvents(id)
    }
}

data class OfferResolveRequest(val offerUri: String, val holderId: String? = null)
data class SessionScopedRequest(val sessionId: String)
data class AuthorizationCodeRequest(val sessionId: String, val authorizationCode: String, val state: String)
data class PreAuthorizedRequest(val sessionId: String, val txCode: String? = null)
data class CredentialRequest(
    val sessionId: String,
    val credentialConfigurationId: String? = null,
    val credentialIdentifier: String? = null,
    val claims: List<String> = emptyList(),
)
data class NotifyRequest(val sessionId: String, val event: NotificationEvent, val description: String? = null)
