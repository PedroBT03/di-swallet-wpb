/**
 * OpenID4VP DCQL conformance scenarios from the catalog.
 */

package di.swallet.wpb.conformance.vp

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.conformance.fixture.ConformanceFixtureLoader
import di.swallet.wpb.conformance.haip.HaipProfileAssertions
import di.swallet.wpb.conformance.support.ConformanceCredentialFactory
import di.swallet.wpb.conformance.support.PresentationConformanceSupport
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

@ConformanceTest
class OpenId4VpDcqlConformanceTest {

    /**
     * DCQL fixture requests a single top-level given_name claim from an SD-JWT PID wallet credential.
     * Happy path reaches consent then dispatches one HAIP-valid SD-JWT VP token.
     */
    @Test
    @ConformanceScenario("vp_sd_jwt_simple_claim")
    fun simpleClaim() = runDcqlCase("fixtures/simple_claim.json")

    /**
     * DCQL fixture targets a nested claim path in the wallet credential.
     * Orchestrator consent and positive VP dispatch succeed under HAIP profile checks.
     */
    @Test
    @ConformanceScenario("vp_sd_jwt_nested_claim")
    fun nestedClaim() = runDcqlCase("fixtures/nested_claim.json")

    /**
     * DCQL fixture requests multiple nested claim segments from one credential query.
     * Session progresses to dispatched positive VP with HAIP assertions on request and token.
     */
    @Test
    @ConformanceScenario("vp_sd_jwt_multi_nested")
    fun multiNested() = runDcqlCase("fixtures/multi_nested.json")

    /**
     * DCQL fixture uses an array wildcard segment in the requested claim path.
     * Matching, consent, and positive VP dispatch complete without error.
     */
    @Test
    @ConformanceScenario("vp_sd_jwt_array_wildcard")
    fun arrayWildcard() = runDcqlCase("fixtures/array_wildcard.json")

    /**
     * DCQL fixture indexes a specific array element in the claim path.
     * Orchestrator reaches DISPATCHED with one positive gateway presentation.
     */
    @Test
    @ConformanceScenario("vp_sd_jwt_array_index")
    fun arrayIndex() = runDcqlCase("fixtures/array_index.json")

    /**
     * DCQL credential entry omits an explicit claims array.
     * Presentation still completes through consent to a positive VP dispatch.
     */
    @Test
    @ConformanceScenario("vp_sd_jwt_claims_absent")
    fun claimsAbsent() = runDcqlCase("fixtures/claims_absent.json")

    /**
     * DCQL credential is declared with an empty claims list.
     * Happy-path consent and positive VP dispatch succeed under HAIP checks.
     */
    @Test
    @ConformanceScenario("vp_sd_jwt_empty_claims")
    fun emptyClaims() = runDcqlCase("fixtures/empty_claims.json")

    /** Loads a DCQL fixture from the classpath and runs the shared happy-path orchestration for that scenario. */
    private fun runDcqlCase(fixturePath: String) = runBlocking {
        val fixture = ConformanceFixtureLoader.loadFixture(fixturePath)
        val request = ConformanceFixtureLoader.toAuthorizationRequest(fixture)
        runHappyPath(request, fixturePath)
    }

    /**
     * Drives consent through positive VP dispatch for a resolved request, asserting HAIP profile
     * checks and that the recording gateway received exactly one positive presentation.
     */
    private suspend fun runHappyPath(request: ResolvedAuthorizationRequest, fixturePath: String) {
        val holderId = "holder-dcql-${fixturePath.substringAfterLast('/')}"
        val credentials = ConformanceCredentialFactory.walletCredentialsForRequest(request, holderId)
        val harness = PresentationConformanceSupport.harness(request, credentials, holderId)

        HaipProfileAssertions.assertOpenId4VpHaipRequestProfile(request)
        HaipProfileAssertions.assertDcqlSdJwtProfile(request)

        val started = harness.orchestrator.startSession(request.requestUri, holderId)
        assertEquals(PresentationState.CONSENT_PENDING, started.state, "fixture=$fixturePath")
        HaipProfileAssertions.assertConsentCandidateClaimsSubset(started)

        val candidateId = started.credentialCandidates.single().candidateId
        val dispatched = harness.orchestrator.submitConsent(
            started.sessionMeta.sessionId,
            ConsentSubmission(
                sessionId = started.sessionMeta.sessionId.toString(),
                holderId = holderId,
                granted = true,
                selectedCredentialIds = listOf(candidateId),
            ),
        )
        assertEquals(PresentationState.DISPATCHED, dispatched.state)
        assertEquals(1, harness.gateway.positiveCount)
        assertNotNull(harness.gateway.lastPositiveToken)
        harness.gateway.lastPositiveToken?.let { HaipProfileAssertions.assertSdJwtVpPresentationToken(it) }
    }
}
