package di.swallet.wpb.presentation

import di.swallet.wpb.observability.InMemorySessionEventStore
import di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PolicyDecision
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.TrustDecision
import di.swallet.wpb.presentation.domain.VpToken
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.matching.CredentialMatcher
import di.swallet.wpb.presentation.orchestration.DefaultPresentationFlowOrchestrator
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import di.swallet.wpb.presentation.policy.PolicyEngine
import di.swallet.wpb.presentation.registry.RegistryValidator
import di.swallet.wpb.presentation.trust.TrustValidator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultPresentationFlowOrchestratorTest {

    private val resolved = ResolvedAuthorizationRequest(
        requestToken = "rt-1",
        requestUri = "http://verifier/request-1",
        clientId = "verifier-demo-client",
        responseMode = PresentationResponseMode.DIRECT_POST,
        nonce = "n1",
        state = "s1",
        responseUri = "http://localhost:8090/callback",
        verifierDisplayName = "Verifier",
        requirements = PresentationRequirements(dcqlQueryJson = "{}", credentialQueryIds = listOf("q1")),
    )

    private class GatewayStub(val request: ResolvedAuthorizationRequest) : OpenId4VpGateway {
        var lastPositiveToken: VpToken? = null
        var negativeCount = 0
        var errorCount = 0
        override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution =
            AuthorizationRequestResolution.Success(request)

        override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome {
            lastPositiveToken = vpToken
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }

        override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome {
            negativeCount++
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }

        override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome {
            errorCount++
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }
    }

    private class StubTrust(private val trusted: Boolean = true) : TrustValidator {
        override fun validate(context: PresentationContext): PresentationContext =
            context.copy(trustDecision = TrustDecision(trusted = trusted, reason = if (trusted) "ok" else "denied"))
    }

    private class StubPolicy(private val allowed: Boolean = true) : PolicyEngine {
        override fun evaluate(context: PresentationContext): PresentationContext =
            context.copy(policyDecision = PolicyDecision(allowed = allowed, reason = if (allowed) "ok" else "denied"))
    }

    private class StubRegistry(private val accepted: Boolean = true) : RegistryValidator {
        override fun validate(context: PresentationContext): PresentationContext =
            context.copy(
                registryDecision = di.swallet.wpb.presentation.domain.RegistryDecision(
                    accepted = accepted,
                    reason = if (accepted) "ok" else "denied",
                    rpIdentifier = context.authorizationRequest?.clientId,
                    sourceEndpoint = "/wrp/{identifier}",
                    intendedUseChecked = true,
                ),
            )
    }

    private class StubMatcher(private val candidates: List<CredentialCandidate>) : CredentialMatcher {
        override fun match(context: PresentationContext): PresentationContext =
            context.copy(credentialCandidates = candidates)
    }

    private class StubVpBuilder : VpTokenBuilder {
        override fun build(context: PresentationContext): PresentationContext =
            context.copy(
                vpToken = VpToken(
                    presentationsByQueryId = mapOf("q1" to listOf("vp-placeholder")),
                    format = CredentialFormat.SD_JWT,
                ),
            )
    }

    private fun newOrchestrator(
        gateway: OpenId4VpGateway = GatewayStub(resolved),
        trust: TrustValidator = StubTrust(),
        registry: RegistryValidator = StubRegistry(),
        policy: PolicyEngine = StubPolicy(),
        matcher: CredentialMatcher = StubMatcher(listOf(candidate())),
        builder: VpTokenBuilder = StubVpBuilder(),
    ) = DefaultPresentationFlowOrchestrator(
        gateway = gateway,
        repository = InMemoryPresentationSessionRepository(),
        trustValidator = trust,
        registryValidator = registry,
        policyEngine = policy,
        credentialMatcher = matcher,
        vpTokenBuilder = builder,
        eventStore = InMemorySessionEventStore(),
    )

    private fun candidate(queryId: String = "q1") = CredentialCandidate(
        candidateId = "c1",
        credentialId = 1L,
        holderId = "holder-1",
        queryId = queryId,
        credentialType = "VerifiableCredential",
        format = CredentialFormat.SD_JWT,
    )

    @Test
    fun `happy path dispatches positive VP on consent`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(gateway = gateway)

        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")
        assertEquals(PresentationState.CONSENT_PENDING, ctx.state)

        val after = orchestrator.submitConsent(
            ctx.sessionMeta.sessionId,
            ConsentSubmission(ctx.sessionMeta.sessionId.toString(), granted = true, selectedCredentialIds = listOf("c1")),
        )
        assertEquals(PresentationState.DISPATCHED, after.state)
        assertNotNull(gateway.lastPositiveToken)
    }

    @Test
    fun `consent denial dispatches negative outcome`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(gateway = gateway)
        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")

        val after = orchestrator.submitConsent(
            ctx.sessionMeta.sessionId,
            ConsentSubmission(ctx.sessionMeta.sessionId.toString(), granted = false),
        )

        assertEquals(PresentationState.DISPATCHED, after.state)
        assertEquals(1, gateway.negativeCount)
    }

    @Test
    fun `untrusted verifier rejects and dispatches negative`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(
            gateway = gateway,
            trust = StubTrust(trusted = false),
        )
        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertTrue(gateway.negativeCount >= 1)
    }

    @Test
    fun `empty candidates after match rejects and dispatches negative`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(
            gateway = gateway,
            matcher = StubMatcher(emptyList()),
            policy = StubPolicy(allowed = true),
        )
        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertEquals(1, gateway.negativeCount)
    }

    @Test
    fun `policy rejection dispatches negative outcome`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(
            gateway = gateway,
            policy = StubPolicy(allowed = false),
        )
        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertEquals(1, gateway.negativeCount)
    }

    @Test
    fun `registry rejection dispatches negative even when trust is valid`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(
            gateway = gateway,
            trust = StubTrust(trusted = true),
            registry = StubRegistry(accepted = false),
            policy = StubPolicy(allowed = true),
            matcher = StubMatcher(listOf(candidate())),
        )
        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertEquals("registry_rejected", ctx.error?.code)
        assertTrue(gateway.negativeCount >= 1)
    }
}
