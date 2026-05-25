package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

class DefaultCredentialMatcherTest {

    private lateinit var repository: WalletCredentialRepository

    @BeforeEach
    fun setUp() {
        repository = mock(WalletCredentialRepository::class.java)
    }

    private fun context(dcqlJson: String, holderId: String = "holder-1"): PresentationContext {
        val now = Instant.now()
        return PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
                holderId = holderId,
                correlationId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(120),
            ),
            state = PresentationState.REQUEST_RESOLVED,
            authorizationRequest = ResolvedAuthorizationRequest(
                requestToken = "rt",
                requestUri = "http://verifier",
                clientId = "verifier-demo-client",
                responseMode = PresentationResponseMode.DIRECT_POST,
                nonce = "n",
                state = "s",
                requirements = PresentationRequirements(
                    dcqlQueryJson = dcqlJson,
                    credentialQueryIds = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `attaches requested claims to candidates`() {
        val credential = WalletCredential(id = 42L, userId = "holder-1", credentialType = "PID", encodedData = "jwt", encryptedDisclosures = "")
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val matcher = DefaultCredentialMatcher(repository, demoMode = false)
        val result = matcher.match(
            context("""{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["given_name"]}]}]}"""),
        )
        val candidate = result.credentialCandidates.single()
        assertEquals("pid", candidate.queryId)
        assertEquals(listOf("given_name"), candidate.requestedClaims)
        assertEquals(42L, candidate.credentialId)
    }

    @Test
    fun `skips mdoc queries silently in non-demo`() {
        val credential = WalletCredential(id = 1L, userId = "holder-1", credentialType = "PID", encodedData = "jwt", encryptedDisclosures = "")
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val matcher = DefaultCredentialMatcher(repository, demoMode = false)
        val result = matcher.match(
            context("""{"credentials":[{"id":"mdoc-query","format":"mso_mdoc","meta":{"doctype_values":["org.iso.18013.5.1.mDL"]}}]}"""),
        )
        assertTrue(result.credentialCandidates.isEmpty())
    }

    @Test
    fun `demo mode synthesises candidate when wallet is empty`() {
        `when`(repository.findByUserId("holder-1")).thenReturn(emptyList())
        val matcher = DefaultCredentialMatcher(repository, demoMode = true)
        val result = matcher.match(
            context("""{"query":[{"type":"Credential","fields":["name"]}]}"""),
        )
        val candidate = result.credentialCandidates.single()
        assertEquals(null, candidate.credentialId)
        assertEquals("query_0", candidate.queryId)
        assertEquals(listOf("name"), candidate.requestedClaims)
        assertEquals(CredentialFormat.SD_JWT, candidate.format)
    }
}
