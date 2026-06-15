/**
 * OpenID4VP negative conformance scenarios that expect early rejection.
 */

package di.swallet.wpb.conformance.vp

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.conformance.fixture.ConformanceFixtureLoader
import di.swallet.wpb.conformance.support.ConformanceCredentialFactory
import di.swallet.wpb.conformance.support.RegistryScenario
import di.swallet.wpb.conformance.support.TrustScenario
import di.swallet.wpb.conformance.support.PresentationConformanceSupport
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.presentation.domain.PresentationState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ConformanceTest
class OpenId4VpNegativeConformanceTest {

    private val baseRequest = ConformanceFixtureLoader.toAuthorizationRequest(
        ConformanceFixtureLoader.loadFixture("fixtures/simple_claim.json"),
    )

    /**
     * Trust scenario marks the authorization client as untrusted before credential matching.
     * startSession ends DISPATCHED with trust_rejected and a negative gateway dispatch.
     */
    @Test
    @ConformanceScenario("vp_trust_untrusted_client")
    fun untrustedClientRejectsBeforeMatching() = runBlocking {
        val holderId = "holder-trust-fail"
        val credentials = ConformanceCredentialFactory.walletCredentialsForRequest(baseRequest, holderId)
        val harness = PresentationConformanceSupport.harness(
            baseRequest,
            credentials,
            holderId,
            trustScenario = TrustScenario.UNTRUSTED_CLIENT,
        )
        val ctx = harness.orchestrator.startSession(baseRequest.requestUri, holderId)
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertEquals("trust_rejected", ctx.error?.code)
        assertTrue(harness.gateway.negativeCount >= 1)
    }

    /**
     * Registry scenario rejects intended use while trust would otherwise pass.
     * Session dispatches negatively with registry_rejected or a negative gateway count.
     */
    @Test
    @ConformanceScenario("vp_registry_intended_use_fail")
    fun registryRejectionDispatchesNegative() = runBlocking {
        val holderId = "holder-registry-fail"
        val credentials = ConformanceCredentialFactory.walletCredentialsForRequest(baseRequest, holderId)
        val harness = PresentationConformanceSupport.harness(
            baseRequest,
            credentials,
            holderId,
            registryScenario = RegistryScenario.REJECTED,
        )
        val ctx = harness.orchestrator.startSession(baseRequest.requestUri, holderId)
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertTrue(
            ctx.error?.code == "registry_rejected" || harness.gateway.negativeCount >= 1,
            "expected registry rejection, got ${ctx.error?.code}",
        )
    }

    /**
     * Harness starts with no wallet credentials for the DCQL request.
     * startSession rejects as DISPATCHED with policy_rejected and a negative dispatch.
     */
    @Test
    @ConformanceScenario("vp_no_matching_credentials")
    fun noMatchingCredentialsPolicyReject() = runBlocking {
        val holderId = "holder-no-match"
        val harness = PresentationConformanceSupport.harness(baseRequest, emptyList(), holderId)
        val ctx = harness.orchestrator.startSession(baseRequest.requestUri, holderId)
        assertEquals(PresentationState.DISPATCHED, ctx.state)
        assertEquals("policy_rejected", ctx.error?.code)
        assertTrue(harness.gateway.negativeCount >= 1)
    }

    /**
     * Session reaches consent pending then the holder denies consent without selecting credentials.
     * Outcome is DISPATCHED with negative dispatch and zero positive VP submissions.
     */
    @Test
    @ConformanceScenario("vp_consent_denied")
    fun consentDeniedDispatchesNegative() = runBlocking {
        val holderId = "holder-consent-deny"
        val credentials = ConformanceCredentialFactory.walletCredentialsForRequest(baseRequest, holderId)
        val harness = PresentationConformanceSupport.harness(baseRequest, credentials, holderId)
        val started = harness.orchestrator.startSession(baseRequest.requestUri, holderId)
        assertEquals(PresentationState.CONSENT_PENDING, started.state)
        val after = harness.orchestrator.submitConsent(
            started.sessionMeta.sessionId,
            ConsentSubmission(
                sessionId = started.sessionMeta.sessionId.toString(),
                holderId = holderId,
                granted = false,
                selectedCredentialIds = emptyList(),
            ),
        )
        assertEquals(PresentationState.DISPATCHED, after.state)
        assertTrue(harness.gateway.negativeCount >= 1)
        assertEquals(0, harness.gateway.positiveCount)
    }
}
