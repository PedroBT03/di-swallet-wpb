/**
 * Tests OpenID4VP HTTP endpoints and presentation session lifecycle.
 */

package di.swallet.wpb.controller

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.observability.SessionEvent
import di.swallet.wpb.observability.SessionEventStore
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.consent.ApprovalMode
import di.swallet.wpb.consent.MinimizationAssessment
import di.swallet.wpb.consent.MinimizationLevel
import di.swallet.wpb.consent.PresentationConsentView
import di.swallet.wpb.consent.VerifierConsentInfo
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.orchestration.PresentationFlowOrchestrator
import kotlinx.coroutines.runBlocking
import di.swallet.wpb.security.AuthenticatedHolderGuardTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OpenId4VpControllerTest {

    private val mapper = ObjectMapper().findAndRegisterModules()

    /**
     * Builds a PresentationContext in CONSENT_PENDING (or the given state) with a minimal
     * resolved authorization request for stub orchestrator controller tests.
     */
    private fun newContext(state: PresentationState = PresentationState.CONSENT_PENDING): PresentationContext {
        val now = Instant.now()
        return PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
                holderId = "holder-1",
                correlationId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(60),
            ),
            state = state,
            authorizationRequest = ResolvedAuthorizationRequest(
                requestToken = "rt",
                requestUri = "u",
                clientId = "verifier",
                responseMode = PresentationResponseMode.DIRECT_POST,
                nonce = "n",
                state = "s",
                requirements = PresentationRequirements(dcqlQueryJson = "{}", credentialQueryIds = emptyList()),
            ),
        )
    }

    private class StubOrchestrator(
        private val produced: PresentationContext,
    ) : PresentationFlowOrchestrator {
        var startCount = 0
        var consentCount = 0
        var lastConsent: ConsentSubmission? = null
        /** Increments startCount and returns the preconfigured stub presentation context. */
        override suspend fun startSession(requestUri: String, holderId: String?): PresentationContext {
            startCount++
            return produced
        }
        /** Returns a fixed PresentationConsentView with OK minimization for the supplied holder. */
        override suspend fun getConsentView(sessionId: UUID, holderId: String): PresentationConsentView =
            PresentationConsentView(
                sessionId = produced.sessionMeta.sessionId,
                state = produced.state,
                holderId = holderId,
                verifier = VerifierConsentInfo("verifier", "Verifier", true, "ok"),
                intendedUse = emptyList(),
                privacyPolicyUri = null,
                registryWarnings = emptyList(),
                minimization = MinimizationAssessment(MinimizationLevel.OK),
                queries = emptyList(),
                choiceGroups = emptyList(),
                approvalMode = ApprovalMode.ALL_OR_NOTHING,
            )

        /** Records the consent decision and returns the stub context with state DISPATCHED. */
        override suspend fun submitConsent(sessionId: UUID, decision: ConsentSubmission): PresentationContext {
            consentCount++
            lastConsent = decision
            return produced.copy(state = PresentationState.DISPATCHED)
        }
        /** Returns the preconfigured stub presentation context unchanged. */
        override suspend fun getSession(sessionId: UUID): PresentationContext = produced
    }

    private class StubEventStore : SessionEventStore {
        private val events = mutableListOf<SessionEvent>()
        /** Appends the event to an in-memory list for later retrieval by session id. */
        override fun record(event: SessionEvent) { events.add(event) }
        /** Filters recorded events to those matching the given presentation session id. */
        override fun getEvents(sessionId: UUID): List<SessionEvent> = events.filter { it.sessionId == sessionId }
    }

    /**
     * Calls getSession on a stub orchestrator and serialises the response to JSON, expecting
     * CONSENT_PENDING state and a correlationId field to be present.
     */
    @Test
    fun `controller exposes lifecycle context as JSON`() = runBlocking {
        val context = newContext()
        val controller = OpenId4VpController(StubOrchestrator(context), StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        val response = controller.getSession(context.sessionMeta.sessionId)

        val json = mapper.writeValueAsString(response)
        assertTrue(json.contains("\"state\":\"CONSENT_PENDING\""))
        assertTrue(json.contains("\"correlationId\""))
    }

    /**
     * Posts a granted consent submission with a selected credential and expects the controller
     * to return DISPATCHED state while forwarding the decision to the orchestrator once.
     */
    @Test
    fun `controller maps consent submission and returns DISPATCHED`() = runBlocking {
        val context = newContext()
        val orchestrator = StubOrchestrator(context)
        val controller = OpenId4VpController(orchestrator, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        val response = controller.consent(
            ConsentSubmission(
                sessionId = context.sessionMeta.sessionId.toString(),
                holderId = "holder-1",
                granted = true,
                selectedCredentialIds = listOf("c1"),
            ),
        )
        assertEquals(PresentationState.DISPATCHED, response.state)
        assertEquals(1, orchestrator.consentCount)
        assertTrue(orchestrator.lastConsent?.granted == true)
    }
}
