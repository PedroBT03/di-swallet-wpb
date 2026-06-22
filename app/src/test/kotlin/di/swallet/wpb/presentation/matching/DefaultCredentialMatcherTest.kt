/**
 * Tests default credential matcher.
 */

package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.CredentialRevocationState
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocEffectiveDocTypeResolver
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.format.sdjwt.SdJwtDisclosureSelector
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.testWalletProperties
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
    private val mdocDocTypeResolver = MdocEffectiveDocTypeResolver(mdocRegistry)
    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService(objectMapper)
    private val disclosureSelector = SdJwtDisclosureSelector(objectMapper, sdJwtService)
    private val disclosureCipher = DisclosureCipherService(testWalletProperties())

    @BeforeEach
    /** Creates a fresh mocked WalletCredentialRepository before each matcher test. */
    fun setUp() {
        repository = mock(WalletCredentialRepository::class.java)
    }

    /** Instantiates DefaultCredentialMatcher with the shared mdoc stack and the given demo-mode flag. */
    private fun matcher(demoMode: Boolean) = DefaultCredentialMatcher(
        repository,
        mdocCodec,
        mdocRegistry,
        mdocDocTypeResolver,
        disclosureCipher,
        disclosureSelector,
        demoMode = demoMode,
        credentialRevocationGuard = RevocationTestSupport.noopGuard(),
    )

    /** Builds a minimal PresentationContext whose authorization request embeds the supplied DCQL JSON. */
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

    /**
     * Wallet holds an SD-JWT PID with a given_name disclosure and the DCQL query requests that claim.
     * Matcher returns one candidate with query id, claim list, and wallet credential id populated.
     */
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

    /**
     * Revoked PID still matches the DCQL query for diagnostics, but is excluded from active candidates.
     */
    @Test
    fun `hasRevokedMatches detects revoked PID that would satisfy query`() {
        val disc = sdJwtService.createDisclosure("given_name", "Pedro")
        val encrypted = disclosureCipher.encrypt(listOf(disc))
        val credential = WalletCredential(
            id = 42L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "jwt",
            encryptedDisclosures = encrypted,
            revocationState = CredentialRevocationState.REVOKED,
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val dcql = """{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["given_name"]}]}]}"""
        val matcher = matcher(demoMode = false)
        val ctx = context(dcql)
        assertTrue(matcher.hasRevokedMatches(ctx))
        assertTrue(matcher.match(ctx).credentialCandidates.isEmpty())
    }

    /**
     * Wallet credential exposes only given_name while the query asks for address.locality.
     * Matcher returns no candidates.
     */
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

    /**
     * Wallet stores nested address disclosures and the query targets address.locality.
     * Matcher produces one matching candidate.
     */
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

    /**
     * Wallet stores a dot-notation address.locality disclosure for a nested DCQL path.
     * Matcher returns one candidate whose requested path serializes to address.locality.
     */
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

    /**
     * Wallet contains an mDL mdoc and a non-demo matcher receives an mso_mdoc DCQL query.
     * Matcher returns one MDOC-format candidate.
     */
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

    /**
     * Wallet stores canonical type MDL while DCQL requests org.iso.18013.5.1.mDL.
     */
    @Test
    fun `matches mdoc when wallet stores canonical MDL label`() {
        val credential = WalletCredential(
            id = 9L,
            userId = "holder-1",
            credentialType = "MDL",
            encodedData = mdocCodec.encode(
                MdocCredentialDocument(
                    docType = "org.iso.18013.5.1.mDL",
                    namespace = "org.iso.18013.5.1",
                    claims = mapOf("driving_privileges" to listOf("B")),
                ),
                binding.deviceCoseKey,
            ),
            encryptedDisclosures = "",
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val result = matcher(demoMode = false).match(
            context("""{"credentials":[{"id":"mdl","format":"mso_mdoc","meta":{"doctype_values":["org.iso.18013.5.1.mDL"]},"claims":[{"path":["driving_privileges"]}]}]}"""),
        ).credentialCandidates.walletBackedOnly()

        assertEquals(1, result.size)
        assertEquals(9L, result.single().credentialId)
        assertEquals("org.iso.18013.5.1.mDL", result.single().credentialType)
    }

    /**
     * Multiple mDL credentials in the wallet each become a separate consent candidate for the same query.
     */
    @Test
    fun `returns all matching mdoc credentials for one query`() {
        fun mdlCredential(id: Long, givenName: String) = WalletCredential(
            id = id,
            userId = "holder-1",
            credentialType = "org.iso.18013.5.1.mDL",
            encodedData = mdocCodec.encode(
                MdocCredentialDocument(
                    docType = "org.iso.18013.5.1.mDL",
                    namespace = "org.iso.18013.5.1",
                    claims = mapOf("given_name" to givenName, "driving_privileges" to listOf("B")),
                ),
                binding.deviceCoseKey,
            ),
            encryptedDisclosures = "",
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(
            listOf(mdlCredential(10L, "Alice"), mdlCredential(11L, "Bob")),
        )

        val result = matcher(demoMode = true).match(
            context("""{"credentials":[{"id":"mdl","format":"mso_mdoc","meta":{"doctype_values":["org.iso.18013.5.1.mDL"]},"claims":[{"path":["driving_privileges"]}]}]}"""),
        ).credentialCandidates.walletBackedOnly()

        assertEquals(2, result.size)
        assertEquals(setOf(10L, 11L), result.mapNotNull { it.credentialId }.toSet())
    }

    /**
     * Demo-mode synthetic candidates are excluded when filtering to wallet-backed credentials only.
     */
    @Test
    fun `walletBackedOnly drops synthetic demo candidates`() {
        `when`(repository.findByUserId("holder-1")).thenReturn(emptyList())
        val result = matcher(demoMode = true).match(
            context("""{"query":[{"type":"Credential","fields":["name"]}]}"""),
        )
        assertEquals(1, result.credentialCandidates.size)
        assertEquals(null, result.credentialCandidates.single().credentialId)
        assertTrue(result.credentialCandidates.walletBackedOnly().isEmpty())
    }

    /**
     * OID4VCI stores configuration id pid_jwt while DCQL requests vct_values PID.
     * Matcher still returns the wallet credential as a candidate.
     */
    @Test
    fun `matches OID4VCI pid_jwt credential against PID vct hint`() {
        val disc = sdJwtService.createDisclosure("given_name", "Alice")
        val encrypted = disclosureCipher.encrypt(listOf(disc))
        val credential = WalletCredential(
            id = 55L,
            userId = "holder-1",
            credentialType = "pid_jwt",
            encodedData = "jwt",
            encryptedDisclosures = encrypted,
        )
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(credential))

        val result = matcher(demoMode = false).match(
            context(
                """{"credentials":[{"id":"pid","format":"vc+sd-jwt","meta":{"vct_values":["PID"]},"claims":[{"path":["given_name"]}]}]}""",
            ),
        )
        val candidate = result.credentialCandidates.single()
        assertEquals("pid", candidate.queryId)
        assertEquals(55L, candidate.credentialId)
        assertEquals("pid_jwt", candidate.credentialType)
    }

    /**
     * Demo mode is on, the wallet is empty, and an emulator-style query is supplied.
     * Matcher synthesizes one SD-JWT candidate with query_0 id and requested field names.
     */
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
