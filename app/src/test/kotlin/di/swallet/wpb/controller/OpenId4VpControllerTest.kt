package di.swallet.wpb.controller

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.observability.SessionEvent
import di.swallet.wpb.observability.SessionEventStore
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.presentation.orchestration.PresentationFlowOrchestrator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OpenId4VpControllerTest {

    private val mapper = ObjectMapper().findAndRegisterModules()

    private fun newContext(state: PresentationState = PresentationState.CONSENT_PENDING): PresentationContext {
        val now = Instant.now()
        return PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
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
        override suspend fun startSession(requestUri: String, holderId: String?): PresentationContext {
            startCount++
            return produced
        }
        override suspend fun submitConsent(sessionId: UUID, decision: ConsentSubmission): PresentationContext {
            consentCount++
            lastConsent = decision
            return produced.copy(state = PresentationState.DISPATCHED)
        }
        override suspend fun getSession(sessionId: UUID): PresentationContext = produced
    }

    private class StubEventStore : SessionEventStore {
        private val events = mutableListOf<SessionEvent>()
        override fun record(event: SessionEvent) { events.add(event) }
        override fun getEvents(sessionId: UUID): List<SessionEvent> = events.filter { it.sessionId == sessionId }
    }

    @Test
    fun `controller exposes lifecycle context as JSON`() = runBlocking {
        val context = newContext()
        val controller = OpenId4VpController(StubOrchestrator(context), StubEventStore())
        val response = controller.getSession(context.sessionMeta.sessionId)

        val json = mapper.writeValueAsString(response)
        assertTrue(json.contains("\"state\":\"CONSENT_PENDING\""))
        assertTrue(json.contains("\"correlationId\""))
    }

    @Test
    fun `controller maps consent submission and returns DISPATCHED`() = runBlocking {
        val context = newContext()
        val orchestrator = StubOrchestrator(context)
        val controller = OpenId4VpController(orchestrator, StubEventStore())
        val response = controller.consent(
            ConsentSubmission(
                sessionId = context.sessionMeta.sessionId.toString(),
                granted = true,
                selectedCredentialIds = listOf("c1"),
            ),
        )
        assertEquals(PresentationState.DISPATCHED, response.state)
        assertEquals(1, orchestrator.consentCount)
        assertTrue(orchestrator.lastConsent?.granted == true)
    }
}
