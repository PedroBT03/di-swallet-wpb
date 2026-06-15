/**
 * Tests presentation transaction mapper.
 */

package di.swallet.wpb.transactionlog

import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.presentation.domain.ClaimPathSegment
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.datadeletion.SupportUriClassifier
import di.swallet.wpb.datadeletion.Ts10InteractingPartyContactBuilder
import di.swallet.wpb.dpareport.RpDnsNameResolver
import di.swallet.wpb.dpareport.Ts10DpaContactBuilder
import di.swallet.wpb.presentation.trust.DefaultVerifierCertificateExtractor
import di.swallet.wpb.presentation.domain.RegistryIntendedUse
import di.swallet.wpb.presentation.domain.RpRegistryRecord
import di.swallet.wpb.presentation.domain.SupervisoryAuthorityContact
import di.swallet.wpb.transactionlog.mapper.PresentationTransactionMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PresentationTransactionMapperTest {
    private val classifier = SupportUriClassifier()
    private val mapper = PresentationTransactionMapper(
        Ts10InteractingPartyContactBuilder(classifier),
        Ts10DpaContactBuilder(classifier),
        RpDnsNameResolver(DefaultVerifierCertificateExtractor()),
    )

    /**
     * Maps a dispatched presentation context where given_name was selected but no attribute values appear in the context.
     * Serialized transaction must omit attribute values and list only the claim path keys under presented claims.
     */
    @Test
    fun `maps claim paths only without attribute values`() {
        val now = Instant.parse("2025-07-29T09:11:20Z")
        val context = PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
                holderId = "holder-1",
                correlationId = "corr-1",
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(600),
            ),
            state = PresentationState.DISPATCHED,
            presentationRequirements = PresentationRequirements(
                dcqlQueryJson = "{}",
                credentialQueryIds = listOf("q1"),
                credentialQueries = listOf(
                    CredentialQuery(
                        id = "q1",
                        format = CredentialFormat.SD_JWT,
                        credentialTypeHints = listOf("urn:eudi:pid:de:1"),
                        requestedClaimPaths = listOf(ClaimPath(listOf(ClaimPathSegment.Key("given_name")))),
                    ),
                ),
            ),
            credentialCandidates = listOf(
                CredentialCandidate(
                    candidateId = "c1",
                    credentialId = 1L,
                    holderId = "holder-1",
                    queryId = "q1",
                    credentialType = "urn:eudi:pid:de:1",
                    format = CredentialFormat.SD_JWT,
                    requestedClaimPaths = listOf(ClaimPath(listOf(ClaimPathSegment.Key("given_name")))),
                ),
            ),
            selectedCredentials = listOf(
                SelectedCredential(
                    candidateId = "c1",
                    credentialId = 1L,
                    holderId = "holder-1",
                    queryId = "q1",
                    credentialType = "urn:eudi:pid:de:1",
                    format = CredentialFormat.SD_JWT,
                    requestedClaimPaths = listOf(ClaimPath(listOf(ClaimPathSegment.Key("given_name")))),
                ),
            ),
            consentDecision = di.swallet.wpb.presentation.domain.ConsentDecision(granted = true),
        )

        val tx = mapper.fromContext(context, now)!!
        val serialized = tx.toString()
        assertFalse(serialized.contains("Pedro"))
        assertEquals(listOf("given_name"), tx.presentation?.listOfClaimsPresented?.first()?.claims)
    }

    /**
     * Maps a presentation context carrying an RP registry record with support URIs and supervisory authority contacts.
     * Transaction must populate interactingPartyContact with country, email, phone, and web channels, and dpaContact with DPA channels.
     */
    @Test
    fun `classifies interacting party contact and dpa contact`() {
        val now = Instant.parse("2025-07-29T09:11:20Z")
        val registry = RpRegistryRecord(
            identifier = "rp-1",
            tradeName = "Demo RP",
            registryUri = "https://registry.example/rp-1",
            supportUris = listOf(
                "mailto:privacy@example.com",
                "tel:+48123456789",
                "https://example.com/privacy",
            ),
            supervisoryAuthority = SupervisoryAuthorityContact(
                name = "DPA",
                country = "PL",
                email = listOf("dpa@example.com"),
                phone = listOf("+48987654321"),
                formUri = listOf("https://dpa.example/form"),
            ),
            intendedUses = listOf(RegistryIntendedUse(purpose = listOf("Age verification"))),
            rawSignedJwt = "jwt",
            rawJwtPayloadJson = "{}",
            rawDataJson = "{}",
        )
        val context = PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
                holderId = "holder-1",
                correlationId = "corr-2",
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(600),
            ),
            state = PresentationState.DISPATCHED,
            registryRecord = registry,
            consentDecision = di.swallet.wpb.presentation.domain.ConsentDecision(granted = true),
        )

        val tx = mapper.fromContext(context, now)!!
        assertEquals(
            listOf("PL", "privacy@example.com", "+48123456789", "https://example.com/privacy"),
            tx.presentation?.interactingPartyContact,
        )
        assertEquals(
            listOf("dpa@example.com", "+48987654321", "https://dpa.example/form"),
            tx.presentation?.dpaContact,
        )
    }
}
