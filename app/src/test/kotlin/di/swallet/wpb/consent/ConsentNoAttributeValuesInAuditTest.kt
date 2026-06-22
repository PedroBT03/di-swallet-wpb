/**
 * Tests that consent audit logs omit raw attribute values.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import di.swallet.wpb.observability.InMemorySessionEventStore
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
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
import di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ConformanceTest
class ConsentNoAttributeValuesInAuditTest {

    /**
     * Runs a presentation session through consent with stub data containing Alice and
     * SECRET_VALUE, then checks the returned session and audit events omit those strings
     * while still recording a consent.granted event.
     */
    @Test
    @ConformanceScenario("consent_no_attribute_values_in_audit")
    fun `consent events and session do not contain attribute values`() = runBlocking {
        val eventStore = InMemorySessionEventStore()
        val consentDeps = ConsentTestSupport.presentationOrchestratorDeps()
        val orchestrator = DefaultPresentationFlowOrchestrator(
            gateway = gatewayWithAliceClaim(),
            repository = InMemoryPresentationSessionRepository(),
            trustValidator = StubTrust(),
            registryValidator = StubRegistry(),
            policyEngine = StubPolicy(),
            credentialMatcher = StubMatcher(),
            vpTokenBuilder = StubVpBuilder(),
            eventStore = eventStore,
            transactionLogger = TransactionLogTestSupport.noopTransactionLogger(),
            consentViewBuilder = consentDeps.consentViewBuilder,
            consentCredentialSelector = consentDeps.consentCredentialSelector,
            consentSessionGuard = consentDeps.consentSessionGuard,
            consentAuditRecorder = consentDeps.consentAuditRecorder,
            minimizationEvaluator = consentDeps.minimizationEvaluator,
            wpbMetrics = WpbMetricsTestSupport.noop(),
        wscaSciGrantService = di.swallet.wpb.security.WscaSciTestSupport.grantService(),
        )

        val ctx = orchestrator.startSession("http://verifier/request", "holder-1")
        val view = orchestrator.getConsentView(ctx.sessionMeta.sessionId, "holder-1")
        assertEquals(PresentationState.CONSENT_PENDING, view.state)
        assertEquals("holder-1", view.holderId)

        val after = orchestrator.submitConsent(
            ctx.sessionMeta.sessionId,
            ConsentSubmission(
                sessionId = ctx.sessionMeta.sessionId.toString(),
                holderId = "holder-1",
                granted = true,
                selectedCredentialIds = listOf("c1"),
            ),
        )

        val forbidden = setOf("Alice", "SECRET_VALUE")
        val sessionJson = after.toString()
        assertFalse(forbidden.any { sessionJson.contains(it) })

        val events = eventStore.getEvents(ctx.sessionMeta.sessionId)
        val eventsBlob = events.joinToString { "${it.type}:${it.attributes}" }
        assertFalse(forbidden.any { eventsBlob.contains(it) })
        assertTrue(events.any { it.type == "consent.granted" })
    }

    /**
     * Returns a stub OpenId4VpGateway that always resolves to a fixed authorization request
     * with query q1, accepting all positive, negative, and error dispatches.
     */
    private fun gatewayWithAliceClaim(): OpenId4VpGateway {
        val resolved = ResolvedAuthorizationRequest(
            requestToken = "rt",
            requestUri = "http://verifier/request",
            clientId = "verifier",
            responseMode = PresentationResponseMode.DIRECT_POST,
            nonce = "n",
            state = "s",
            requirements = PresentationRequirements(
                dcqlQueryJson = "{}",
                credentialQueryIds = listOf("q1"),
            ),
        )
        return object : OpenId4VpGateway {
            /** Returns a successful resolution with the prebuilt authorization request. */
            override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution =
                AuthorizationRequestResolution.Success(resolved)
            /** Accepts the positive VP token dispatch without contacting a real verifier. */
            override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken) =
                PresentationDispatchOutcome.VerifierAccepted(null)
            /** Accepts a negative presentation response without contacting a real verifier. */
            override suspend fun dispatchNegative(requestToken: String) =
                PresentationDispatchOutcome.VerifierAccepted(null)
            /** Accepts an error response dispatch without contacting a real verifier. */
            override suspend fun dispatchError(errorToken: String) =
                PresentationDispatchOutcome.VerifierAccepted(null)
        }
    }

    private class StubTrust : TrustValidator {
        /** Marks the presentation context as trusted without performing PKIX validation. */
        override fun validate(context: PresentationContext) =
            context.copy(trustDecision = TrustDecision(trusted = true))
    }

    private class StubRegistry : RegistryValidator {
        /** Accepts the verifier with intended-use checked without querying a real RP registry. */
        override fun validate(context: PresentationContext) =
            context.copy(
                registryDecision = di.swallet.wpb.presentation.domain.RegistryDecision(
                    accepted = true,
                    intendedUseChecked = true,
                ),
            )
    }

    private class StubPolicy : PolicyEngine {
        /** Allows the presentation without evaluating real wallet policy rules. */
        override fun evaluate(context: PresentationContext) =
            context.copy(policyDecision = PolicyDecision(allowed = true))
    }

    private class StubMatcher : CredentialMatcher {
        /** Supplies a single PID SD-JWT candidate c1 for query q1 without reading the credential store. */
        override fun match(context: PresentationContext): PresentationContext =
            context.copy(
                credentialCandidates = listOf(
                    CredentialCandidate(
                        candidateId = "c1",
                        credentialId = 1L,
                        holderId = "holder-1",
                        queryId = "q1",
                        credentialType = "PID",
                        format = CredentialFormat.SD_JWT,
                    ),
                ),
            )

        override fun hasRevokedMatches(context: PresentationContext): Boolean = false
    }

    private class StubVpBuilder : VpTokenBuilder {
        /** Attaches a placeholder VP token map for query q1 without building a real SD-JWT presentation. */
        override fun build(context: PresentationContext): PresentationContext =
            context.copy(vpToken = VpToken(mapOf("q1" to listOf("vp"))))
    }
}
