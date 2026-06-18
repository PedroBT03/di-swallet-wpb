/**
 * Tests data deletion request service.
 */

package di.swallet.wpb.datadeletion

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.config.DataDeletionRequestProperties
import di.swallet.wpb.presentation.registry.RpRegistryResolver
import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import di.swallet.wpb.transactionlog.domain.Ts10Identifier
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.service.TransactionLogService
import di.swallet.wpb.transactionlog.service.TransactionLogSummary
import di.swallet.wpb.transactionlog.service.TransactionLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@ConformanceTest
class DataDeletionRequestServiceTest {
    private val transactionLogService = mock(TransactionLogService::class.java)
    private val registryResolver = mock(RpRegistryResolver::class.java)
    private val transactionLogger = mock(TransactionLogger::class.java)
    private val properties = DataDeletionRequestProperties()
    private val classifier = SupportUriClassifier()
    private val contactBuilder = Ts10InteractingPartyContactBuilder(classifier)
    private lateinit var service: DataDeletionRequestService

    /** Wires DataDeletionRequestService with mocked transaction log, registry, and contact resolver dependencies. */
    @BeforeEach
    fun setUp() {
        val contactResolver = DeletionContactResolver(
            transactionLogService = transactionLogService,
            contactBuilder = contactBuilder,
            registryResolver = registryResolver,
            properties = properties,
        )
        service = DataDeletionRequestService(
            transactionLogService = transactionLogService,
            contactResolver = contactResolver,
            actionBuilder = DeletionActionBuilder(DeletionMailTemplateBuilder(properties)),
            mapper = DataDeletionRequestMapper(),
            transactionLogger = transactionLogger,
        )
    }

    /**
     * Initiates deletion for a completed presentation with web, email, and phone contacts and
     * expects actions ordered WEB then EMAIL then PHONE, including a mailto privacy URI.
     */
    @Test
    @ConformanceScenario("data_deletion_request_service")
    fun `initiate returns mailto and web actions ordered web email phone`() {
        val presentationId = "pres-1"
        `when`(transactionLogService.get("holder-1", presentationId))
            .thenReturn(presentationTransaction(presentationId))

        val response = service.initiate(
            DataDeletionInitiateRequest(
                holderId = "holder-1",
                presentationTransactionId = presentationId,
                deleteAllPresented = true,
            ),
        )

        assertEquals(presentationId, response.sourcePresentationTransactionId)
        assertEquals(listOf("WEB", "EMAIL", "PHONE"), response.availableActions.map { it.channel })
        assertTrue(response.availableActions[1].uri.startsWith("mailto:privacy@rp.eu"))
        assertNotNull(response.transactionId)
        assertTrue(response.transactionId!!.isNotBlank())
    }

    /**
     * Calls initiate without deleteAllPresented or a claims list and expects
     * ResponseStatusException because at least one deletion scope must be specified.
     */
    @Test
    fun `initiate rejects when neither deleteAllPresented nor claims provided`() {
        val presentationId = "pres-2"
        `when`(transactionLogService.get("holder-1", presentationId))
            .thenReturn(presentationTransaction(presentationId))

        assertThrows<ResponseStatusException> {
            service.initiate(
                DataDeletionInitiateRequest(
                    holderId = "holder-1",
                    presentationTransactionId = presentationId,
                ),
            )
        }
    }

    /**
     * Mocks one completed presentation transaction for the holder and expects listEligible to
     * return it with hasStoredDeletionContacts true.
     */
    @Test
    fun `listEligible returns completed presentations with claims`() {
        `when`(transactionLogService.list("holder-1")).thenReturn(
            listOf(
                TransactionLogSummary(
                    transactionId = "pres-1",
                    transactionType = Ts10TransactionType.Presentation.name,
                    transactionResult = Ts10TransactionResult.Completed.name,
                    occurredAt = Instant.parse("2025-07-29T09:11:20Z"),
                    deletedByUser = false,
                ),
            ),
        )
        `when`(transactionLogService.get("holder-1", "pres-1")).thenReturn(presentationTransaction("pres-1"))

        val eligible = service.listEligible("holder-1")
        assertEquals(1, eligible.size)
        assertEquals("pres-1", eligible.first().presentationTransactionId)
        assertTrue(eligible.first().hasStoredDeletionContacts)
    }

    /**
     * Completed demo-style presentation with only interactingPartyName (no EUID/registrar URL)
     * is still eligible for privacy listing.
     */
    @Test
    fun `listEligible accepts presentation with display name only`() {
        `when`(transactionLogService.list("holder-1")).thenReturn(
            listOf(
                TransactionLogSummary(
                    transactionId = "pres-demo",
                    transactionType = Ts10TransactionType.Presentation.name,
                    transactionResult = Ts10TransactionResult.Completed.name,
                    occurredAt = Instant.parse("2025-07-29T09:11:20Z"),
                    deletedByUser = false,
                ),
            ),
        )
        `when`(transactionLogService.get("holder-1", "pres-demo")).thenReturn(
            Ts10Transaction(
                transactionIdentifier = "pres-demo",
                time = "2025-07-29T09:11:20",
                transactionType = Ts10TransactionType.Presentation.name,
                transactionResult = Ts10TransactionResult.Completed.name,
                presentation = Ts10Presentation(
                    interactingPartyName = "verifier-demo-client",
                    listOfClaimsPresented = listOf(
                        Ts10ClaimInfo(credentialIdentifier = "PID", claims = listOf("given_name")),
                    ),
                ),
            ),
        )

        val eligible = service.listEligible("holder-1")
        assertEquals(1, eligible.size)
        assertEquals("pres-demo", eligible.first().presentationTransactionId)
    }

    /**
     * When no deletion contacts can be resolved, returns an empty action list and a notice
     * instead of failing the request.
     */
    @Test
    fun `initiate returns notice when no deletion contacts are available`() {
        val presentationId = "pres-empty"
        `when`(transactionLogService.get("holder-1", presentationId)).thenReturn(
            Ts10Transaction(
                transactionIdentifier = presentationId,
                time = "2025-07-29T09:11:20",
                transactionType = Ts10TransactionType.Presentation.name,
                transactionResult = Ts10TransactionResult.Completed.name,
                presentation = Ts10Presentation(
                    interactingPartyName = "verifier-demo-client",
                    listOfClaimsPresented = listOf(
                        Ts10ClaimInfo(credentialIdentifier = "PID", claims = listOf("given_name")),
                    ),
                ),
            ),
        )

        val response = service.initiate(
            DataDeletionInitiateRequest(
                holderId = "holder-1",
                presentationTransactionId = presentationId,
                deleteAllPresented = true,
            ),
        )

        assertTrue(response.availableActions.isEmpty())
        assertNull(response.transactionId)
        assertEquals(properties.noContactNotice, response.userNotice)
    }

    /**
     * Demo-style presentation without stored RP contacts uses provider fallback when configured.
     */
    @Test
    fun `initiate uses provider fallback when no stored deletion contacts`() {
        properties.providerFallbackRp.apply {
            country = "PT"
            email = "privacy@demo-verifier.local"
            webUri = "http://localhost:8081/privacy"
        }
        val presentationId = "pres-demo"
        `when`(transactionLogService.get("holder-1", presentationId)).thenReturn(
            Ts10Transaction(
                transactionIdentifier = presentationId,
                time = "2025-07-29T09:11:20",
                transactionType = Ts10TransactionType.Presentation.name,
                transactionResult = Ts10TransactionResult.Completed.name,
                presentation = Ts10Presentation(
                    interactingPartyName = "verifier-demo-client",
                    listOfClaimsPresented = listOf(
                        Ts10ClaimInfo(credentialIdentifier = "PID", claims = listOf("given_name")),
                    ),
                ),
            ),
        )

        val response = service.initiate(
            DataDeletionInitiateRequest(
                holderId = "holder-1",
                presentationTransactionId = presentationId,
                deleteAllPresented = true,
            ),
        )

        assertEquals(presentationId, response.sourcePresentationTransactionId)
        assertTrue(response.availableActions.isNotEmpty())
        assertTrue(response.availableActions.any { it.uri.startsWith("mailto:privacy@demo-verifier.local") })
        assertNotNull(response.userNotice)
    }

    /** Builds a completed presentation transaction with web, email, and phone deletion contacts for mocking. */
    private fun presentationTransaction(id: String): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = id,
            time = "2025-07-29T09:11:20",
            transactionType = Ts10TransactionType.Presentation.name,
            transactionResult = Ts10TransactionResult.Completed.name,
            presentation = Ts10Presentation(
                interactingPartyIdentifier = Ts10Identifier(
                    type = "http://data.europa.eu/eudi/id/EUID",
                    identifier = "rp-1",
                ),
                interactingPartyName = "Demo RP",
                interactingPartyContact = listOf(
                    "PL",
                    "privacy@rp.eu",
                    "+48111222333",
                    "https://rp.eu/privacy",
                ),
                registrarURL = "https://registry.example/rp-1",
                listOfClaimsPresented = listOf(
                    Ts10ClaimInfo(credentialIdentifier = "PID", claims = listOf("given_name")),
                ),
            ),
        )
}
