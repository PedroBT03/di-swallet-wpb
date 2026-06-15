package di.swallet.wpb.presentation

import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.observability.InMemorySessionEventStore
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
import di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.VpToken
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.matching.DefaultCredentialMatcher
import di.swallet.wpb.consent.ConsentTestSupport
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import di.swallet.wpb.presentation.orchestration.DefaultPresentationFlowOrchestrator
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import di.swallet.wpb.presentation.policy.DefaultPolicyEngine
import di.swallet.wpb.presentation.registry.RegistryValidator
import di.swallet.wpb.presentation.trust.AccessCertificateValidationResult
import di.swallet.wpb.presentation.trust.AccessCertificateValidationService
import di.swallet.wpb.presentation.trust.DefaultTrustValidator
import di.swallet.wpb.presentation.trust.VerifierCertificateExtractor
import di.swallet.wpb.presentation.trust.VerifierCertificateMaterial
import di.swallet.wpb.trust.core.TrustBindingRule
import di.swallet.wpb.trust.core.TrustSnapshot
import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import di.swallet.wpb.trust.core.TrustSnapshotResolver
import di.swallet.wpb.trust.core.TrustedEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * Locks in the lifecycle fix: with the **real** trust validator,
 * credential matcher and policy engine, the wallet must reach
 * `CONSENT_PENDING` when there is at least one matching wallet credential —
 * even with `demo-mode = false`. The previous bug had policy run before
 * matching, which made every non-demo session collapse into `REJECTED`.
 */
class DefaultPresentationFlowOrchestratorRealBeansTest {

    private val resolved = ResolvedAuthorizationRequest(
        requestToken = "rt-real",
        requestUri = "http://verifier/req",
        clientId = "verifier-demo-client",
        responseMode = PresentationResponseMode.DIRECT_POST,
        nonce = "nonce-1",
        state = "state-1",
        responseUri = "http://verifier/direct_post",
        verifierDisplayName = "Verifier",
        requirements = PresentationRequirements(
            dcqlQueryJson = """{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["given_name"]}]}]}""",
            credentialQueryIds = listOf("pid"),
            requestedFormats = setOf(CredentialFormat.SD_JWT),
        ),
    )

    private class GatewayStub(val request: ResolvedAuthorizationRequest) : OpenId4VpGateway {
        var positiveCount = 0
        var negativeCount = 0
        override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution =
            AuthorizationRequestResolution.Success(request)
        override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome {
            positiveCount++
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }
        override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome {
            negativeCount++
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }
        override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome =
            PresentationDispatchOutcome.VerifierAccepted(null)
    }

    private class StubVpBuilder : VpTokenBuilder {
        override fun build(context: PresentationContext): PresentationContext =
            context.copy(
                vpToken = VpToken(
                    presentationsByQueryId = mapOf("pid" to listOf("VP-STUB")),
                    format = CredentialFormat.SD_JWT,
                ),
            )
    }

    private fun orchestrator(
        repository: WalletCredentialRepository,
        allowed: String = "verifier-demo-client",
        demoMode: Boolean = false,
        registryAccepted: Boolean = true,
    ): Pair<DefaultPresentationFlowOrchestrator, GatewayStub> {
        val gateway = GatewayStub(resolved)
        val trustProperties = OpenId4VpProperties().apply {
            this.demoMode = demoMode
            this.trust.allowedClientIds = allowed
            this.trust.allowFailOpenInDemoMode = false
        }
        val trustSnapshot = TrustSnapshot(
            trustAnchors = emptyList(),
            entities = mapOf(
                "verifier-demo-client" to TrustedEntity(
                    entityId = "verifier-demo-client",
                    bindings = setOf(TrustBindingRule("client_id", "verifier-demo-client")),
                ),
            ),
            source = "test",
            loadedAt = Instant.now(),
        )
        val resolver = object : TrustSnapshotResolver {
            override fun currentAvailability(): TrustSnapshotAvailability = TrustSnapshotAvailability.Available(trustSnapshot)
        }
        val extractor = object : VerifierCertificateExtractor {
            override fun extract(request: ResolvedAuthorizationRequest): VerifierCertificateMaterial? {
                val cert = mock(X509Certificate::class.java)
                return VerifierCertificateMaterial(chain = listOf(cert), leaf = cert)
            }
        }
        val certValidator = object : AccessCertificateValidationService {
            override fun validate(
                requestClientId: String,
                material: VerifierCertificateMaterial,
                snapshot: TrustSnapshot,
            ): AccessCertificateValidationResult = AccessCertificateValidationResult.Trusted
        }
        val registryValidator = object : RegistryValidator {
            override fun validate(context: PresentationContext): PresentationContext =
                context.copy(
                    registryDecision = di.swallet.wpb.presentation.domain.RegistryDecision(
                        accepted = registryAccepted,
                        reason = if (registryAccepted) "ok" else "registry denied",
                        rpIdentifier = context.authorizationRequest?.clientId,
                        sourceEndpoint = "/wrp/{identifier}",
                        intendedUseChecked = true,
                    ),
                )
        }
        val consentDeps = ConsentTestSupport.presentationOrchestratorDeps(
            repository = repository,
            openId4VpProperties = trustProperties,
        )
        val orchestrator = DefaultPresentationFlowOrchestrator(
            gateway = gateway,
            repository = InMemoryPresentationSessionRepository(),
            trustValidator = DefaultTrustValidator(
                properties = trustProperties,
                trustSnapshotResolver = resolver,
                certificateExtractor = extractor,
                certificateValidationService = certValidator,
            ),
            registryValidator = registryValidator,
            policyEngine = DefaultPolicyEngine(trustProperties),
            credentialMatcher = PresentationTestSupport.credentialMatcher(
                repository,
                MdocTestSupport.stack().codec,
                MdocDocTypeRegistry(),
                demoMode = demoMode,
            ),
            vpTokenBuilder = StubVpBuilder(),
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
        return orchestrator to gateway
    }

    @Test
    fun `non-demo session with matching wallet credential reaches consent`() = runBlocking {
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findByUserId("holder-1")).thenReturn(
            listOf(PresentationTestSupport.sdJwtCredential(1L, "holder-1", "given_name")),
        )

        val (orchestrator, _) = orchestrator(repository)
        val ctx = orchestrator.startSession("http://verifier/req", "holder-1")
        assertEquals(PresentationState.CONSENT_PENDING, ctx.state)
        assertEquals(1, ctx.credentialCandidates.size)
        assertEquals(listOf("given_name"), ctx.credentialCandidates.single().requestedClaims)
    }

    @Test
    fun `non-demo session without matching credentials rejects and dispatches negative`() = runBlocking {
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findByUserId("holder-1")).thenReturn(emptyList())

        val (orchestrator, gateway) = orchestrator(repository)
        val ctx = orchestrator.startSession("http://verifier/req", "holder-1")
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertTrue(gateway.negativeCount >= 1)
        assertEquals("policy_rejected", ctx.error?.code)
    }

    @Test
    fun `non-demo session with untrusted client_id rejects before matching`() = runBlocking {
        val repository = mock(WalletCredentialRepository::class.java)
        val (orchestrator, gateway) = orchestrator(repository, allowed = "another-verifier")
        val ctx = orchestrator.startSession("http://verifier/req", "holder-1")
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertEquals("trust_rejected", ctx.error?.code)
        assertTrue(gateway.negativeCount >= 1)
    }

    @Test
    fun `consent submission dispatches positive VP`() = runBlocking {
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findByUserId("holder-1")).thenReturn(
            listOf(PresentationTestSupport.sdJwtCredential(1L, "holder-1", "given_name")),
        )

        val (orchestrator, gateway) = orchestrator(repository)
        val ctx = orchestrator.startSession("http://verifier/req", "holder-1")
        val candidateId = ctx.credentialCandidates.single().candidateId
        val after = orchestrator.submitConsent(
            ctx.sessionMeta.sessionId,
            ConsentSubmission(
                sessionId = ctx.sessionMeta.sessionId.toString(),
                holderId = "holder-1",
                granted = true,
                selectedCredentialIds = listOf(candidateId),
            ),
        )
        assertEquals(PresentationState.DISPATCHED, after.state)
        assertEquals(1, gateway.positiveCount)
        assertNotNull(after.dispatchOutcome)
    }
}
