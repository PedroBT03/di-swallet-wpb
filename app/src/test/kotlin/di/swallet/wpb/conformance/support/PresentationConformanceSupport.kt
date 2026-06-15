/**
 * Shared helpers to run presentation flows and record gateway submissions in conformance tests.
 */

package di.swallet.wpb.conformance.support

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.consent.ConsentTestSupport
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import di.swallet.wpb.observability.InMemorySessionEventStore
import di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.PresentationTestSupport
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.VpToken
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.orchestration.DefaultPresentationFlowOrchestrator
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import di.swallet.wpb.presentation.policy.DefaultPolicyEngine
import di.swallet.wpb.presentation.registry.RegistryValidator
import di.swallet.wpb.presentation.trust.TrustValidator
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

data class PresentationConformanceHarness(
    val orchestrator: DefaultPresentationFlowOrchestrator,
    val gateway: RecordingGateway,
    val repository: WalletCredentialRepository,
)

class RecordingGateway(private val request: ResolvedAuthorizationRequest) : OpenId4VpGateway {
    var positiveCount = 0
    var negativeCount = 0
    var lastPositiveToken: VpToken? = null

    /** Always returns the preconfigured resolved authorization request regardless of the request URI. */
    override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution =
        AuthorizationRequestResolution.Success(request)

    /** Records the positive VP token and increments the positive dispatch counter for assertions. */
    override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome {
        positiveCount++
        lastPositiveToken = vpToken
        return PresentationDispatchOutcome.VerifierAccepted(null)
    }

    /** Increments the negative dispatch counter without forwarding to a real verifier. */
    override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome {
        negativeCount++
        return PresentationDispatchOutcome.VerifierAccepted(null)
    }

    /** Acknowledges error dispatches without contacting a remote verifier endpoint. */
    override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome =
        PresentationDispatchOutcome.VerifierAccepted(null)
}

object PresentationConformanceSupport {

    /**
     * Wires a full DefaultPresentationFlowOrchestrator with stub trust, registry, and VP builder
     * collaborators, returning the orchestrator together with a gateway that records dispatches.
     */
    fun harness(
        request: ResolvedAuthorizationRequest,
        credentials: List<WalletCredential>,
        holderId: String = "holder-conformance",
        trustScenario: TrustScenario = TrustScenario.TRUSTED,
        registryScenario: RegistryScenario = RegistryScenario.ACCEPTED,
        demoMode: Boolean = false,
        vpTokenBuilder: VpTokenBuilder? = null,
    ): PresentationConformanceHarness {
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findByUserId(holderId)).thenReturn(credentials)

        val properties = NegativeScenarioFactory.trustProperties(
            allowedClientId = request.clientId,
            demoMode = demoMode,
        )
        val gateway = RecordingGateway(request)
        val consentDeps = ConsentTestSupport.presentationOrchestratorDeps(
            repository = repository,
            openId4VpProperties = properties,
        )
        val trustValidator: TrustValidator = NegativeScenarioFactory.trustValidator(
            properties = properties,
            scenario = trustScenario,
            allowedClientId = request.clientId,
        )
        val registryValidator: RegistryValidator = NegativeScenarioFactory.registryValidator(registryScenario)

        val orchestrator = DefaultPresentationFlowOrchestrator(
            gateway = gateway,
            repository = InMemoryPresentationSessionRepository(),
            trustValidator = trustValidator,
            registryValidator = registryValidator,
            policyEngine = DefaultPolicyEngine(properties),
            credentialMatcher = PresentationTestSupport.credentialMatcher(
                repository,
                MdocTestSupport.stack().codec,
                MdocDocTypeRegistry(),
                demoMode = demoMode,
            ),
            vpTokenBuilder = vpTokenBuilder ?: StubVpTokenBuilder(),
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
        return PresentationConformanceHarness(orchestrator, gateway, repository)
    }

    private class StubVpTokenBuilder : VpTokenBuilder {
        /** Attaches a minimal SD-JWT VP stub with one presentation segment per query id. */
        override fun build(context: di.swallet.wpb.presentation.domain.PresentationContext) =
            context.copy(
                vpToken = VpToken(
                    presentationsByQueryId = mapOf(
                        (context.credentialCandidates.firstOrNull()?.queryId ?: "pid") to listOf("VP-STUB~d1~KB.JWT.SIG"),
                    ),
                    format = di.swallet.wpb.presentation.domain.CredentialFormat.SD_JWT,
                ),
            )
    }
}
