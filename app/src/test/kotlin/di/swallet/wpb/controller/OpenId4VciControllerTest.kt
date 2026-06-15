/**
 * Tests OpenID4VCI HTTP endpoints and issuance session lifecycle.
 */

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
import di.swallet.wpb.security.AuthenticatedHolderGuardTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OpenId4VciControllerTest {

    private val mapper = ObjectMapper().findAndRegisterModules()

    /**
     * Builds a minimal IssuanceContext with a fresh session id, holder-1, and the given
     * issuance state for stub orchestrator controller tests.
     */
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

        /** Increments resolveCalls and returns the preconfigured stub issuance context. */
        override fun resolveOffer(offerUri: String, holderId: String?): IssuanceContext {
            resolveCalls++; return produced
        }
        /** Increments prepareCalls and records the session id before returning the stub context. */
        override fun prepareAuthorization(sessionId: UUID): IssuanceContext {
            prepareCalls++; lastSessionId = sessionId; return produced
        }
        /** Increments codeCalls and returns the stub context with state AUTHORIZED. */
        override fun completeAuthorizationCode(sessionId: UUID, authorizationCode: String, state: String): IssuanceContext {
            codeCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.AUTHORIZED)
        }
        /** Increments preAuthCalls and returns the stub context with state AUTHORIZED. */
        override fun completePreAuthorizedCode(sessionId: UUID, txCode: String?): IssuanceContext {
            preAuthCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.AUTHORIZED)
        }
        /** Records the credential request payload and returns the stub context with state CREDENTIAL_ISSUED. */
        override fun requestCredential(sessionId: UUID, request: IssuanceRequest): IssuanceContext {
            requestCalls++; lastSessionId = sessionId; lastRequest = request
            return produced.copy(state = IssuanceState.CREDENTIAL_ISSUED)
        }
        /** Increments deferredCalls and returns the stub context with state DEFERRED_ISSUED. */
        override fun queryDeferred(sessionId: UUID): IssuanceContext {
            deferredCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.DEFERRED_ISSUED)
        }
        /** Increments notifyCalls and returns the stub context with state NOTIFIED. */
        override fun notify(sessionId: UUID, event: NotificationEvent, description: String?): IssuanceContext {
            notifyCalls++; lastSessionId = sessionId; return produced.copy(state = IssuanceState.NOTIFIED)
        }
        /** Returns a fixed IssuanceConsentView derived from the stub context and supplied holder id. */
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

        /** Returns the stub context with state CREDENTIAL_ISSUED after consent submission. */
        override fun submitIssuanceConsent(sessionId: UUID, decision: IssuanceConsentSubmission): IssuanceContext =
            produced.copy(state = IssuanceState.CREDENTIAL_ISSUED)

        /** Returns the preconfigured stub issuance context unchanged. */
        override fun getSession(sessionId: UUID): IssuanceContext = produced
    }

    private class StubEventStore : IssuanceEventStore {
        private val events = mutableListOf<IssuanceEvent>()
        /** Appends the event to an in-memory list for later retrieval by session id. */
        override fun record(event: IssuanceEvent) { events.add(event) }
        /** Filters recorded events to those matching the given issuance session id. */
        override fun getEvents(sessionId: UUID): List<IssuanceEvent> =
            events.filter { it.sessionId == sessionId }
    }

    /**
     * Calls getSession for an AUTHORIZED issuance context and serialises the response,
     * expecting JSON to contain the state and correlationId fields.
     */
    @Test
    fun `getSession serialises issuance context as JSON`() {
        val produced = ctx(IssuanceState.AUTHORIZED)
        val controller = OpenId4VciController(StubOrchestrator(produced), StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        val response = controller.getSession(produced.sessionMeta.sessionId)
        val json = mapper.writeValueAsString(response)
        assertTrue(json.contains("\"state\":\"AUTHORIZED\""))
        assertTrue(json.contains("\"correlationId\""))
    }

    /**
     * Posts an offer URI to resolveOffer and expects the stub orchestrator resolveCalls
     * counter to increment exactly once.
     */
    @Test
    fun `resolveOffer delegates to orchestrator`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        controller.resolveOffer(OfferResolveRequest(offerUri = "openid-credential-offer://test", holderId = "h"))
        assertEquals(1, orch.resolveCalls)
    }

    /**
     * Calls prepareAuthorization with a session id and expects the orchestrator to receive
     * that same id in lastSessionId after one prepare call.
     */
    @Test
    fun `prepareAuthorization forwards session id`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        controller.prepareAuthorization(SessionScopedRequest(produced.sessionMeta.sessionId.toString()))
        assertEquals(1, orch.prepareCalls)
        assertEquals(produced.sessionMeta.sessionId, orch.lastSessionId)
    }

    /**
     * Submits an authorization code and state for a session and expects the response state
     * to become AUTHORIZED with one orchestrator code completion call.
     */
    @Test
    fun `authorize code forwards code and state`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        val ctxOut = controller.completeAuthorizationCode(
            AuthorizationCodeRequest(produced.sessionMeta.sessionId.toString(), "code-1", "state-1"),
        )
        assertEquals(IssuanceState.AUTHORIZED, ctxOut.state)
        assertEquals(1, orch.codeCalls)
    }

    /**
     * Submits a pre-authorized transaction code and expects the orchestrator preAuthCalls
     * counter to increment once.
     */
    @Test
    fun `pre-authorized forwards tx code`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        controller.completePreAuthorized(PreAuthorizedRequest(produced.sessionMeta.sessionId.toString(), txCode = "1234"))
        assertEquals(1, orch.preAuthCalls)
    }

    /**
     * Posts a credential request with configuration id pid_jwt and claims given_name, then
     * expects the orchestrator to capture that payload in lastRequest.
     */
    @Test
    fun `credential request carries the request payload`() {
        val produced = ctx()
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
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

    /**
     * Sends a CREDENTIAL_ACCEPTED notification with description stored and expects the
     * orchestrator notifyCalls counter to increment once.
     */
    @Test
    fun `notify forwards event type and description`() {
        val produced = ctx(IssuanceState.CREDENTIAL_ISSUED)
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        controller.notify(
            NotifyRequest(produced.sessionMeta.sessionId.toString(), NotificationEvent.CREDENTIAL_ACCEPTED, "stored"),
        )
        assertEquals(1, orch.notifyCalls)
    }

    /**
     * Queries deferred issuance for a DEFERRED_PENDING session and expects the response
     * state to become DEFERRED_ISSUED with one orchestrator deferred call.
     */
    @Test
    fun `queryDeferred forwards session id`() {
        val produced = ctx(IssuanceState.DEFERRED_PENDING)
        val orch = StubOrchestrator(produced)
        val controller = OpenId4VciController(orch, StubEventStore(), AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        val out = controller.queryDeferred(SessionScopedRequest(produced.sessionMeta.sessionId.toString()))
        assertEquals(IssuanceState.DEFERRED_ISSUED, out.state)
        assertEquals(1, orch.deferredCalls)
    }

    /**
     * Pre-records an offer.received event in the stub store, then calls getSessionEvents
     * and expects one event with that type to be returned.
     */
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
        val controller = OpenId4VciController(StubOrchestrator(produced), store, AuthenticatedHolderGuardTestSupport.noop(), AuthenticatedHolderGuardTestSupport.noopOid4SessionAccessGuard())
        val events = controller.getSessionEvents(produced.sessionMeta.sessionId)
        assertEquals(1, events.size)
        assertEquals("offer.received", events.first().type)
    }
}
