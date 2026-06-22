/**
 * Tests presentation flow orchestration with stubbed collaborators.
 */

package di.swallet.wpb.presentation

import di.swallet.wpb.observability.InMemorySessionEventStore
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
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
import di.swallet.wpb.consent.ConsentTestSupport
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
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
        /** Returns the fixed resolved authorization request for any request URI. */
        override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution =
            AuthorizationRequestResolution.Success(request)

        /** Captures the positive VP token and acknowledges dispatch to the verifier. */
        override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome {
            lastPositiveToken = vpToken
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }

        /** Increments the negative dispatch counter and returns a successful outcome. */
        override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome {
            negativeCount++
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }

        /** Increments the error dispatch counter and returns a successful outcome. */
        override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome {
            errorCount++
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }
    }

    private class StubTrust(private val trusted: Boolean = true) : TrustValidator {
        /** Sets trustDecision to trusted or denied based on the constructor flag. */
        override fun validate(context: PresentationContext): PresentationContext =
            context.copy(trustDecision = TrustDecision(trusted = trusted, reason = if (trusted) "ok" else "denied"))
    }

    private class StubPolicy(private val allowed: Boolean = true) : PolicyEngine {
        /** Sets policyDecision.allowed according to the constructor flag with a matching reason string. */
        override fun evaluate(context: PresentationContext): PresentationContext =
            context.copy(policyDecision = PolicyDecision(allowed = allowed, reason = if (allowed) "ok" else "denied"))
    }

    private class StubRegistry(private val accepted: Boolean = true) : RegistryValidator {
        /** Copies registryDecision with accepted or denied based on the constructor flag. */
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

    private class StubMatcher(
        private val candidates: List<CredentialCandidate>,
        private val revokedMatches: Boolean = false,
    ) : CredentialMatcher {
        /** Replaces credentialCandidates with the list supplied at construction time. */
        override fun match(context: PresentationContext): PresentationContext =
            context.copy(credentialCandidates = candidates)

        override fun hasRevokedMatches(context: PresentationContext): Boolean = revokedMatches
    }

    private class StubVpBuilder : VpTokenBuilder {
        /** Attaches a placeholder SD-JWT VP token with one presentation for query q1. */
        override fun build(context: PresentationContext): PresentationContext =
            context.copy(
                vpToken = VpToken(
                    presentationsByQueryId = mapOf("q1" to listOf("vp-placeholder")),
                    format = CredentialFormat.SD_JWT,
                ),
            )
    }

    /** Builds DefaultPresentationFlowOrchestrator with injectable stub collaborators and real consent dependencies. */
    private fun newOrchestrator(
        gateway: OpenId4VpGateway = GatewayStub(resolved),
        trust: TrustValidator = StubTrust(),
        registry: RegistryValidator = StubRegistry(),
        policy: PolicyEngine = StubPolicy(),
        matcher: CredentialMatcher = StubMatcher(listOf(candidate())),
        builder: VpTokenBuilder = StubVpBuilder(),
    ): DefaultPresentationFlowOrchestrator {
        val consentDeps = ConsentTestSupport.presentationOrchestratorDeps()
        return DefaultPresentationFlowOrchestrator(
            gateway = gateway,
            repository = InMemoryPresentationSessionRepository(),
            trustValidator = trust,
            registryValidator = registry,
            policyEngine = policy,
            credentialMatcher = matcher,
            vpTokenBuilder = builder,
            eventStore = InMemorySessionEventStore(),
            transactionLogger = TransactionLogTestSupport.noopTransactionLogger(),
            consentViewBuilder = consentDeps.consentViewBuilder,
            consentCredentialSelector = consentDeps.consentCredentialSelector,
            consentSessionGuard = consentDeps.consentSessionGuard,
            consentAuditRecorder = consentDeps.consentAuditRecorder,
            minimizationEvaluator = consentDeps.minimizationEvaluator,
            wpbMetrics = WpbMetricsTestSupport.noop(),
        wscaSciGrantService = di.swallet.wpb.security.WscaSciTestSupport.grantService(),
        )
    }

    /** Returns a minimal SD-JWT CredentialCandidate for the given query id. */
    private fun candidate(queryId: String = "q1") = CredentialCandidate(
        candidateId = "c1",
        credentialId = 1L,
        holderId = "holder-1",
        queryId = queryId,
        credentialType = "VerifiableCredential",
        format = CredentialFormat.SD_JWT,
    )

    /**
     * Stubbed trust, policy, and matcher succeed and the holder grants consent for one candidate.
     * Session reaches DISPATCHED and the gateway receives a positive VP token.
     */
    @Test
    fun `happy path dispatches positive VP on consent`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(gateway = gateway)

        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")
        assertEquals(PresentationState.CONSENT_PENDING, ctx.state)

        val after = orchestrator.submitConsent(
            ctx.sessionMeta.sessionId,
            ConsentSubmission(
                sessionId = ctx.sessionMeta.sessionId.toString(),
                holderId = "holder-1",
                granted = true,
                selectedCredentialIds = listOf("c1"),
            ),
        )
        assertEquals(PresentationState.DISPATCHED, after.state)
        assertNotNull(gateway.lastPositiveToken)
    }

    /**
     * Session reaches consent pending then the holder denies consent.
     * State becomes DISPATCHED and the gateway records one negative dispatch.
     */
    @Test
    fun `consent denial dispatches negative outcome`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(gateway = gateway)
        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")

        val after = orchestrator.submitConsent(
            ctx.sessionMeta.sessionId,
            ConsentSubmission(
                sessionId = ctx.sessionMeta.sessionId.toString(),
                holderId = "holder-1",
                granted = false,
            ),
        )

        assertEquals(PresentationState.DISPATCHED, after.state)
        assertEquals(1, gateway.negativeCount)
    }

    /**
     * Trust validator is stubbed to reject the verifier before matching runs.
     * startSession ends DISPATCHED with at least one negative gateway dispatch.
     */
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

    /**
     * Matcher returns no candidates while policy would otherwise allow the request.
     * startSession rejects immediately as DISPATCHED with one negative dispatch.
     */
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
        assertEquals("no_matching_credentials", ctx.error?.code)
        assertEquals(1, gateway.negativeCount)
    }

    /**
     * Matcher finds revoked credentials that would match but no active candidates.
     * startSession rejects with credentials_revoked instead of a generic no-match error.
     */
    @Test
    fun `revoked matching credentials yield credentials_revoked error`() = runBlocking {
        val gateway = GatewayStub(resolved)
        val orchestrator = newOrchestrator(
            gateway = gateway,
            matcher = StubMatcher(emptyList(), revokedMatches = true),
            policy = StubPolicy(allowed = true),
        )
        val ctx = orchestrator.startSession("http://verifier/request-1", "holder-1")
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertEquals("credentials_revoked", ctx.error?.code)
        assertEquals(1, gateway.negativeCount)
    }

    /**
     * Policy engine stub denies the presentation after trust succeeds.
     * startSession finishes DISPATCHED and sends one negative outcome to the gateway.
     */
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

    /**
     * Trust and policy pass but registry validation rejects the relying party.
     * Session dispatches negatively with error code registry_rejected.
     */
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
