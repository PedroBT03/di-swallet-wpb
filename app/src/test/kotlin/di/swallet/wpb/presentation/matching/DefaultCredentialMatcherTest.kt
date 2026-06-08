package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.format.sdjwt.SdJwtDisclosureSelector
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.service.format.DisclosureCipherService
import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.revocation.RevocationTestSupport
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
    private val binding = MdocTestSupport.holderBinding()
    private val mdocCodec = MdocTestSupport.stack(holderBindings = listOf(binding)).codec
    private val mdocRegistry = MdocDocTypeRegistry()
    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService(objectMapper)
    private val disclosureSelector = SdJwtDisclosureSelector(objectMapper, sdJwtService)
    private val disclosureCipher = DisclosureCipherService(WalletProperties())

    @BeforeEach
    fun setUp() {
        repository = mock(WalletCredentialRepository::class.java)
    }

    private fun matcher(demoMode: Boolean) = DefaultCredentialMatcher(
        repository,
        mdocCodec,
        mdocRegistry,
        disclosureCipher,
        disclosureSelector,
        demoMode = demoMode,
        credentialRevocationGuard = RevocationTestSupport.noopGuard(),
    )

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
        val disc = sdJwtService.createDisclosure("given_name", "Pedro")
        val encrypted = disclosureCipher.encrypt(listOf(disc))
        val credential = WalletCredential(
            id = 42L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "jwt",
            encryptedDisclosures = encrypted,
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val result = matcher(demoMode = false).match(
            context("""{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["given_name"]}]}]}"""),
        )
        val candidate = result.credentialCandidates.single()
        assertEquals("pid", candidate.queryId)
        assertEquals(listOf("given_name"), candidate.requestedClaims)
        assertEquals(42L, candidate.credentialId)
    }

    @Test
    fun `excludes SD-JWT credential that cannot satisfy nested path`() {
        val disc = sdJwtService.createDisclosure("given_name", "Pedro")
        val encrypted = disclosureCipher.encrypt(listOf(disc))
        val credential = WalletCredential(
            id = 7L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "jwt",
            encryptedDisclosures = encrypted,
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val result = matcher(demoMode = false).match(
            context("""{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["address","locality"]}]}]}"""),
        )
        assertTrue(result.credentialCandidates.isEmpty())
    }

    @Test
    fun `matches SD-JWT credential with nested object disclosures`() {
        val issued = sdJwtService.createNestedObjectDisclosures(
            "address",
            mapOf("locality" to "Lisbon"),
        )
        val encrypted = disclosureCipher.encrypt(issued.disclosures)
        val credential = WalletCredential(
            id = 9L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "jwt",
            encryptedDisclosures = encrypted,
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val result = matcher(demoMode = false).match(
            context("""{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["address","locality"]}]}]}"""),
        )
        assertEquals(1, result.credentialCandidates.size)
    }

    @Test
    fun `matches SD-JWT credential with PID dot-notation disclosure`() {
        val disc = sdJwtService.createDisclosure("address.locality", "Lisbon")
        val encrypted = disclosureCipher.encrypt(listOf(disc))
        val credential = WalletCredential(
            id = 8L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "jwt",
            encryptedDisclosures = encrypted,
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val result = matcher(demoMode = false).match(
            context("""{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["address","locality"]}]}]}"""),
        )
        assertEquals(1, result.credentialCandidates.size)
        assertEquals(
            "address.locality",
            result.credentialCandidates.single().requestedClaimPaths.single().toDotNotation(),
        )
    }

    @Test
    fun `matches mdoc queries in non-demo when wallet contains mdoc credential`() {
        val credential = WalletCredential(
            id = 1L,
            userId = "holder-1",
            credentialType = "org.iso.18013.5.1.mDL",
            encodedData = mdocCodec.encode(
                MdocCredentialDocument(
                    docType = "org.iso.18013.5.1.mDL",
                    namespace = "org.iso.18013.5.1",
                    claims = mapOf("given_name" to "Alice"),
                ),
                binding.deviceCoseKey,
            ),
            encryptedDisclosures = "",
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val result = matcher(demoMode = false).match(
            context("""{"credentials":[{"id":"mdoc-query","format":"mso_mdoc","meta":{"doctype_values":["org.iso.18013.5.1.mDL"]},"claims":[{"path":["given_name"]}]}]}"""),
        )
        assertEquals(1, result.credentialCandidates.size)
        assertEquals(CredentialFormat.MDOC, result.credentialCandidates.first().format)
    }

    @Test
    fun `demo mode synthesises candidate when wallet is empty`() {
        `when`(repository.findByUserId("holder-1")).thenReturn(emptyList())
        val result = matcher(demoMode = true).match(
            context("""{"query":[{"type":"Credential","fields":["name"]}]}"""),
        )
        val candidate = result.credentialCandidates.single()
        assertEquals(null, candidate.credentialId)
        assertEquals("query_0", candidate.queryId)
        assertEquals(listOf("name"), candidate.requestedClaims)
        assertEquals(CredentialFormat.SD_JWT, candidate.format)
    }
}
