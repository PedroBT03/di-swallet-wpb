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
import di.swallet.wpb.transactionlog.mapper.PresentationTransactionMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PresentationTransactionMapperTest {
    private val mapper = PresentationTransactionMapper()

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
}
