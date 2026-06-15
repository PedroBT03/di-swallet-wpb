/**
 * REST endpoints for the OpenID4VP presentation session lifecycle.
 */

package di.swallet.wpb.controller

import di.swallet.wpb.observability.SessionEvent
import di.swallet.wpb.observability.SessionEventStore
import di.swallet.wpb.consent.PresentationConsentView
import di.swallet.wpb.openid4vp.protocol.AuthorizationStartRequest
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.orchestration.PresentationFlowOrchestrator
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
 * Routes OpenID4VP presentation requests to the flow orchestrator with holder access checks.
 */
@RestController
@RequestMapping("/openid4vp")
@Tag(name = "OpenID4VP", description = "OpenID4VP + HAIP presentation lifecycle endpoints")
class OpenId4VpController(
    private val presentationFlowOrchestrator: PresentationFlowOrchestrator,
    private val eventStore: SessionEventStore,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
    private val oid4SessionAccessGuard: Oid4SessionAccessGuard,
) {

    /**
     * Starts a new presentation session from a verifier authorization request URI.
     */
    @PostMapping("/authorize")
    @Operation(summary = "Start OpenID4VP session")
    suspend fun authorize(@RequestBody request: AuthorizationStartRequest): PresentationContext {
        return presentationFlowOrchestrator.startSession(
            requestUri = request.requestUri,
            holderId = request.holderId,
        )
    }

    /**
     * Returns the holder consent view for the WPI consent screen.
     */
    @GetMapping("/session/{id}/consent-view")
    @Operation(
        summary = "Get presentation consent view for WPI",
        description = "Preferred endpoint for holder consent screens. Requires matching holderId.",
    )
    suspend fun getConsentView(
        @PathVariable id: UUID,
        @RequestParam holderId: String,
    ): PresentationConsentView {
        oid4SessionAccessGuard.requireSessionHolder(holderId)
        return presentationFlowOrchestrator.getConsentView(id, holderId)
    }

    /**
     * Submits holder consent or rejection and requires FIDO2 authentication.
     */
    @PostMapping("/consent")
    @Operation(summary = "Submit holder consent (requires FIDO2)")
    suspend fun consent(@RequestBody request: ConsentSubmission): PresentationContext {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return presentationFlowOrchestrator.submitConsent(
            sessionId = UUID.fromString(request.sessionId),
            decision = request,
        )
    }

    /**
     * Returns the low-level presentation session context for debugging or advanced clients.
     */
    @GetMapping("/session/{id}")
    @Operation(
        summary = "Get presentation session",
        description = "Low-level lifecycle context. WPI consent UI should use GET /session/{id}/consent-view instead.",
    )
    suspend fun getSession(@PathVariable id: UUID): PresentationContext {
        val session = presentationFlowOrchestrator.getSession(id)
        oid4SessionAccessGuard.requireSessionHolder(session.sessionMeta.holderId)
        return session
    }

    /**
     * Returns the audit event timeline for a presentation session.
     */
    @GetMapping("/session/{id}/events")
    @Operation(summary = "Get session events")
    suspend fun getSessionEvents(@PathVariable id: UUID): List<SessionEvent> {
        val session = presentationFlowOrchestrator.getSession(id)
        oid4SessionAccessGuard.requireSessionHolder(session.sessionMeta.holderId)
        return eventStore.getEvents(id)
    }
}
