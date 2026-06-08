package di.swallet.wpb.datadeletion

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

class DataDeletionRequestServiceTest {
    private val transactionLogService = mock(TransactionLogService::class.java)
    private val registryResolver = mock(RpRegistryResolver::class.java)
    private val transactionLogger = mock(TransactionLogger::class.java)
    private val properties = DataDeletionRequestProperties()
    private val classifier = SupportUriClassifier()
    private val contactBuilder = Ts10InteractingPartyContactBuilder(classifier)
    private lateinit var service: DataDeletionRequestService

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

    @Test
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
        assertTrue(response.transactionId.isNotBlank())
    }

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
