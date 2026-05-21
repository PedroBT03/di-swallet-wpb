package di.swallet.wpb.presentation

import di.swallet.wpb.openid4vp.protocol.*
import di.swallet.wpb.presentation.domain.*
import di.swallet.wpb.presentation.matching.CredentialMatcher
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.orchestration.DefaultPresentationFlowOrchestrator
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import di.swallet.wpb.presentation.policy.PolicyEngine
import di.swallet.wpb.presentation.trust.TrustValidator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DefaultPresentationFlowOrchestratorTest {

    private class GatewayStub(val request: ResolvedAuthorizationRequest) : di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway {
        override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution = AuthorizationRequestResolution.Success(request)

        override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome = PresentationDispatchOutcome.VerifierAccepted(null)

        override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome = PresentationDispatchOutcome.VerifierRejected

        override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome = PresentationDispatchOutcome.VerifierRejected
    }

    private class AlwaysTrust : TrustValidator {
        override fun validate(context: PresentationContext): PresentationContext = context.copy(trustDecision = TrustDecision(trusted = true))
    }

    private class AllowPolicy : PolicyEngine {
        override fun evaluate(context: PresentationContext): PresentationContext = context.copy(policyDecision = PolicyDecision(allowed = true))
    }

    private class SimpleMatcher : CredentialMatcher {
        override fun match(context: PresentationContext): PresentationContext {
            val cand = CredentialCandidate(
                candidateId = "c1",
                credentialId = 1L,
                holderId = "holder-1",
                queryId = context.presentationRequirements?.credentialQueryIds?.firstOrNull() ?: "q1",
                credentialType = "VerifiableCredential",
                format = CredentialFormat.SD_JWT,
            )
            return context.copy(credentialCandidates = listOf(cand))
        }
    }

    private class SimpleVpBuilder : VpTokenBuilder {
        override fun build(context: PresentationContext): PresentationContext {
            val vp = VpToken(mapOf("q1" to listOf("vp-placeholder")), format = CredentialFormat.SD_JWT, rawValue = "stub")
            return context.copy(vpToken = vp)
        }
    }

    @Test
    fun `orchestrator happy path creates session then dispatches on consent`() = runBlocking {
        val repo = InMemoryPresentationSessionRepository()

        val resolved = ResolvedAuthorizationRequest(
            requestToken = "rt-1",
            requestUri = "http://verifier/request-1",
            clientId = "verifier-1",
            responseMode = PresentationResponseMode.DIRECT_POST,
            nonce = "n1",
            state = "s1",
            responseUri = "http://localhost:8090/callback",
            redirectUri = null,
            verifierDisplayName = "Verifier",
            requirements = PresentationRequirements(dcqlQueryJson = "{}", credentialQueryIds = listOf("q1")),
        )

        val gateway = GatewayStub(resolved)
        val orchestrator = DefaultPresentationFlowOrchestrator(
            gateway = gateway,
            repository = repo,
            trustValidator = AlwaysTrust(),
            policyEngine = AllowPolicy(),
            credentialMatcher = SimpleMatcher(),
            vpTokenBuilder = SimpleVpBuilder(),
            eventStore = di.swallet.wpb.observability.InMemorySessionEventStore(),
        )

        val ctx = orchestrator.startSession("http://verifier/request-1", holderId = "holder-1")
        assertEquals(PresentationState.CONSENT_PENDING, ctx.state)

        val sessionId = ctx.sessionMeta.sessionId

        val consent = ConsentSubmission(sessionId = sessionId.toString(), granted = true, selectedCredentialIds = listOf("c1"))
        val after = orchestrator.submitConsent(sessionId, consent)

        assertEquals(PresentationState.DISPATCHED, after.state)
        assertNotNull(after.dispatchOutcome)
    }
}
