package di.swallet.wpb.controller

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.consent.IssuanceConsentView
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceSessionMetadata
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.orchestration.IssuanceFlowOrchestrator
import di.swallet.wpb.observability.IssuanceEvent
import di.swallet.wpb.observability.IssuanceEventStore
import di.swallet.wpb.openid4vci.protocol.IssuanceConsentSubmission
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OpenId4VciControllerTest {

    private val mapper = ObjectMapper().findAndRegisterModules()

    private fun ctx(state: IssuanceState = IssuanceState.OFFER_RESOLVED): IssuanceContext {
        val now = Instant.now()
        return IssuanceContext(
            sessionMeta = IssuanceSessionMetadata(
                sessionId = UUID.randomUUID(),
                holderId = "holder-1",
                correlationId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(120),
            ),
            state = state,
        )
    }

    private class StubOrchestrator(private val produced: IssuanceContext) : IssuanceFlowOrchestrator {
        var resolveCalls = 0
        var prepareCalls = 0
        var codeCalls = 0
        var preAuthCalls = 0
        var requestCalls = 0
        var deferredCalls = 0
        var notifyCalls = 0
        var lastSessionId: UUID? = null
        var lastRequest: IssuanceRequest? = null

        override fun resolveOffer(offerUri: String, holderId: String?): IssuanceContext {
            resolveCalls++; return produced
        }
        override fun prepareAuthorization(sessionId: UUID): IssuanceContext {
            prepareCalls++; lastSessionId = sessionId; return produced
        }
        override fun completeAuthorizationCode(sessionId: UUID, authorizationCode: String, state: String): IssuanceContext {
            codeCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.AUTHORIZED)
        }
        override fun completePreAuthorizedCode(sessionId: UUID, txCode: String?): IssuanceContext {
            preAuthCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.AUTHORIZED)
        }
        override fun requestCredential(sessionId: UUID, request: IssuanceRequest): IssuanceContext {
            requestCalls++; lastSessionId = sessionId; lastRequest = request
            return produced.copy(state = IssuanceState.CREDENTIAL_ISSUED)
        }
        override fun queryDeferred(sessionId: UUID): IssuanceContext {
            deferredCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.DEFERRED_ISSUED)
        }
        override fun notify(sessionId: UUID, event: NotificationEvent, description: String?): IssuanceContext {
            notifyCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.NOTIFIED)
        }
        override fun getConsentView(sessionId: UUID, holderId: String): IssuanceConsentView =
            IssuanceConsentView(
                sessionId = produced.sessionMeta.sessionId,
                state = produced.state,
                holderId = holderId,
                issuer = di.swallet.wpb.consent.IssuerConsentInfo("https://issuer.example", "Issuer"),
                credentialConfigurationId = "pid_jwt",
                format = IssuanceCredentialFormat.SD_JWT_VC,
                deviceBound = true,
                claimPreview = emptyList(),
            )

        override fun submitIssuanceConsent(sessionId: UUID, decision: IssuanceConsentSubmission): IssuanceContext =
            produced.copy(state = IssuanceState.CREDENTIAL_ISSUED)

        override fun getSession(sessionId: UUID): IssuanceContext = produced
    }

    private class StubEventStore : IssuanceEventStore {
        private val events = mutableListOf<IssuanceEvent>()
        override fun record(event: IssuanceEvent) { events.add(event) }
        override fun getEvents(sessionId: UUID): List<IssuanceEvent> =
            events.filter { it.sessionId == sessionId }
    }

    @Test
    fun `getSession serialises issuance context as JSON`() {
        val produced = ctx(IssuanceState.AUTHORIZED)
        val controller = OpenId4VciController(StubOrchestrator(produced), StubEventStore())
        val response = controller.getSession(produced.sessionMeta.sessionId)
        val json = mapper.writeValueAsString(response)
        assertTrue(json.contains("\"state\":\"AUTHORIZED\""))
        assertTrue(json.contains("\"correlationId\""))
    }

    @Test
    fun `resolveOffer delegates to orchestrator`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore())
        controller.resolveOffer(OfferResolveRequest(offerUri = "openid-credential-offer://test", holderId = "h"))
        assertEquals(1, orch.resolveCalls)
    }

    @Test
    fun `prepareAuthorization forwards session id`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore())
        controller.prepareAuthorization(SessionScopedRequest(produced.sessionMeta.sessionId.toString()))
        assertEquals(1, orch.prepareCalls)
        assertEquals(produced.sessionMeta.sessionId, orch.lastSessionId)
    }

    @Test
    fun `authorize code forwards code and state`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore())
        val ctxOut = controller.completeAuthorizationCode(
            AuthorizationCodeRequest(produced.sessionMeta.sessionId.toString(), "code-1", "state-1"),
        )
        assertEquals(IssuanceState.AUTHORIZED, ctxOut.state)
        assertEquals(1, orch.codeCalls)
    }

    @Test
    fun `pre-authorized forwards tx code`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore())
        controller.completePreAuthorized(PreAuthorizedRequest(produced.sessionMeta.sessionId.toString(), txCode = "1234"))
        assertEquals(1, orch.preAuthCalls)
    }

    @Test
    fun `credential request carries the request payload`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore())
        controller.requestCredential(
            CredentialRequest(
                sessionId = produced.sessionMeta.sessionId.toString(),
                credentialConfigurationId = "pid_jwt",
                claims = listOf("given_name"),
            ),
        )
        assertEquals(1, orch.requestCalls)
        assertNotNull(orch.lastRequest)
        assertEquals("pid_jwt", orch.lastRequest?.credentialConfigurationId)
        assertEquals(listOf("given_name"), orch.lastRequest?.claims)
    }

    @Test
    fun `notify forwards event type and description`() {
        val produced = ctx(IssuanceState.CREDENTIAL_ISSUED)
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore())
        controller.notify(
            NotifyRequest(produced.sessionMeta.sessionId.toString(), NotificationEvent.CREDENTIAL_ACCEPTED, "stored"),
        )
        assertEquals(1, orch.notifyCalls)
    }

    @Test
    fun `queryDeferred forwards session id`() {
        val produced = ctx(IssuanceState.DEFERRED_PENDING)
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore())
        val out = controller.queryDeferred(SessionScopedRequest(produced.sessionMeta.sessionId.toString()))
        assertEquals(IssuanceState.DEFERRED_ISSUED, out.state)
        assertEquals(1, orch.deferredCalls)
    }

    @Test
    fun `getSessionEvents reads from the event store`() {
        val produced = ctx()
        val store = StubEventStore()
        store.record(
            IssuanceEvent(
                sessionId = produced.sessionMeta.sessionId,
                correlationId = produced.sessionMeta.correlationId,
                timestamp = Instant.now(),
                type = "offer.received",
                state = produced.state,
            ),
        )
        val controller = OpenId4VciController(StubOrchestrator(produced), store)
        val events = controller.getSessionEvents(produced.sessionMeta.sessionId)
        assertEquals(1, events.size)
        assertEquals("offer.received", events.first().type)
    }
}
