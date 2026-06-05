package di.swallet.wpb.presentation.policy

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.RegistryCredentialDescriptor
import di.swallet.wpb.presentation.domain.RegistryDecision
import di.swallet.wpb.presentation.domain.RegistryIntendedUse
import di.swallet.wpb.presentation.domain.RpRegistryRecord
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.presentation.domain.TrustDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DefaultPolicyEngineTest {

    private fun engine(
        demoMode: Boolean = false,
        registryEnabled: Boolean = false,
        requirePrivacyPolicyUri: Boolean = false,
    ): DefaultPolicyEngine = DefaultPolicyEngine(
        OpenId4VpProperties().apply {
            this.demoMode = demoMode
            registry.enabled = registryEnabled
            registry.requirePrivacyPolicyUri = requirePrivacyPolicyUri
        },
    )

    private fun baseContext(
        trusted: Boolean = true,
        registryDecision: RegistryDecision? = null,
        registryRecord: RpRegistryRecord? = null,
        candidates: List<CredentialCandidate> = listOf(sampleCandidate()),
        responseMode: PresentationResponseMode = PresentationResponseMode.DIRECT_POST,
        credentialQueries: List<CredentialQuery> = listOf(sampleQuery()),
    ): PresentationContext {
        val now = Instant.now()
        return PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
                correlationId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(120),
            ),
            state = PresentationState.VERIFIER_VALIDATED,
            authorizationRequest = ResolvedAuthorizationRequest(
                requestToken = "rt",
                requestUri = "http://verifier",
                clientId = "rp-123",
                responseMode = responseMode,
                nonce = "n",
                state = "s",
                requirements = PresentationRequirements(
                    dcqlQueryJson = "{}",
                    credentialQueryIds = listOf("pid"),
                    credentialQueries = credentialQueries,
                ),
            ),
            trustDecision = TrustDecision(trusted = trusted, reason = if (trusted) "ok" else "denied"),
            registryDecision = registryDecision,
            registryRecord = registryRecord,
            presentationRequirements = PresentationRequirements(
                dcqlQueryJson = "{}",
                credentialQueryIds = listOf("pid"),
                credentialQueries = credentialQueries,
            ),
            credentialCandidates = candidates,
        )
    }

    @Test
    fun `allows when registry disabled and trust plus candidates pass`() {
        val result = engine().evaluate(baseContext())
        assertTrue(result.policyDecision?.allowed == true)
    }

    @Test
    fun `rejects when registry enabled but decision missing`() {
        val result = engine(registryEnabled = true).evaluate(baseContext(registryDecision = null))
        assertFalse(result.policyDecision?.allowed == true)
        assertTrue(result.policyDecision?.reason?.contains("Registry validation required") == true)
    }

    @Test
    fun `rejects when registry enabled but record missing`() {
        val result = engine(registryEnabled = true).evaluate(
            baseContext(
                registryDecision = RegistryDecision(
                    accepted = true,
                    intendedUseChecked = true,
                    rpIdentifier = "rp-123",
                ),
                registryRecord = null,
            ),
        )
        assertFalse(result.policyDecision?.allowed == true)
        assertTrue(result.policyDecision?.reason?.contains("Registry record missing") == true)
    }

    @Test
    fun `rejects when registry enabled but intended use not checked`() {
        val result = engine(registryEnabled = true).evaluate(
            baseContext(
                registryDecision = RegistryDecision(accepted = true, intendedUseChecked = false),
                registryRecord = sampleRecord(),
            ),
        )
        assertFalse(result.policyDecision?.allowed == true)
        assertTrue(result.policyDecision?.reason?.contains("intended-use check") == true)
    }

    @Test
    fun `rejects when requested claims exceed registry intended use`() {
        val result = engine(registryEnabled = true).evaluate(
            baseContext(
                registryDecision = RegistryDecision(accepted = true, intendedUseChecked = true),
                registryRecord = sampleRecord(claimPaths = listOf("family_name")),
                credentialQueries = listOf(sampleQuery(claim = "given_name")),
            ),
        )
        assertFalse(result.policyDecision?.allowed == true)
        assertTrue(result.policyDecision?.reason?.contains("exceed registry") == true)
    }

    @Test
    fun `accepts when registry enabled and record covers requested credentials`() {
        val result = engine(registryEnabled = true).evaluate(
            baseContext(
                registryDecision = RegistryDecision(accepted = true, intendedUseChecked = true),
                registryRecord = sampleRecord(claimPaths = listOf("given_name")),
            ),
        )
        assertTrue(result.policyDecision?.allowed == true)
    }

    @Test
    fun `rejects unsupported response mode`() {
        val result = engine().evaluate(
            baseContext(responseMode = PresentationResponseMode.QUERY),
        )
        assertFalse(result.policyDecision?.allowed == true)
        assertEquals("Unsupported response mode 'query'", result.policyDecision?.reason)
    }

    @Test
    fun `requires privacy policy uri when configured`() {
        val result = engine(registryEnabled = true, requirePrivacyPolicyUri = true).evaluate(
            baseContext(
                registryDecision = RegistryDecision(accepted = true, intendedUseChecked = true),
                registryRecord = sampleRecord(privacyPolicyUris = emptyList()),
            ),
        )
        assertFalse(result.policyDecision?.allowed == true)
        assertTrue(result.policyDecision?.reason?.contains("privacy policy") == true)
    }

    private fun sampleQuery(claim: String = "given_name"): CredentialQuery =
        CredentialQuery(
            id = "pid",
            format = CredentialFormat.SD_JWT,
            requestedClaimPaths = listOf(ClaimPath.key(claim)),
        )

    private fun sampleCandidate(): CredentialCandidate =
        CredentialCandidate(
            candidateId = "cand-1",
            credentialId = 1L,
            holderId = "holder-1",
            queryId = "pid",
            credentialType = "PID",
            format = CredentialFormat.SD_JWT,
            requestedClaimPaths = listOf(ClaimPath.key("given_name")),
        )

    private fun sampleRecord(
        claimPaths: List<String> = listOf("given_name"),
        privacyPolicyUris: List<String> = listOf("https://rp.example/privacy"),
    ): RpRegistryRecord = RpRegistryRecord(
        identifier = "rp-123",
        intendedUses = listOf(
            RegistryIntendedUse(
                intendedUseIdentifier = "iu-1",
                privacyPolicyUris = privacyPolicyUris,
                credentials = listOf(
                    RegistryCredentialDescriptor(
                        format = "dc+sd-jwt",
                        claimPaths = claimPaths,
                    ),
                ),
            ),
        ),
        rawSignedJwt = "jwt",
        rawJwtPayloadJson = "{}",
        rawDataJson = "{}",
    )
}
