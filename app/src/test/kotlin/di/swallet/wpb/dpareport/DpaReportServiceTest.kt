/**
 * Tests dpa report service.
 */

package di.swallet.wpb.dpareport

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.ProviderFallbackDpa
import di.swallet.wpb.datadeletion.SupportUriClassifier
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
import org.mockito.Mockito.mock
import java.time.Instant

class DpaReportServiceTest {
    private val transactionLogService = mock(TransactionLogService::class.java)
    private val registryResolver = mock(RpRegistryResolver::class.java)
    private val transactionLogger = mock(TransactionLogger::class.java)
    private val classifier = SupportUriClassifier()
    private val properties = DpaReportProperties()
    private lateinit var service: DpaReportService

    /** Wires DpaReportService with mocked transaction log, registry, and contact resolver dependencies. */
    @BeforeEach
    fun setUp() {
        properties.providerFallbackDpa = ProviderFallbackDpa()
        val contactResolver = DpaContactResolver(
            transactionLogService = transactionLogService,
            dpaContactBuilder = Ts10DpaContactBuilder(classifier),
            registryResolver = registryResolver,
            rpDnsNameResolver = RpDnsNameResolver(di.swallet.wpb.presentation.trust.DefaultVerifierCertificateExtractor()),
            properties = properties,
        )
        service = DpaReportService(
            transactionLogService = transactionLogService,
            contactResolver = contactResolver,
            actionBuilder = DpaActionBuilder(DpaMailTemplateBuilder(properties)),
            mapper = DpaReportMapper(),
            transactionLogger = transactionLogger,
        )
    }

    /**
     * Initiates a report for a completed presentation with stored DPA contacts and expects
     * WEB/EMAIL/PHONE actions, certificate-derived dnsName, and a substantiation document.
     */
    @Test
    fun `initiate returns actions for stored dpa contacts`() {
        org.mockito.Mockito.`when`(transactionLogService.get("holder-1", "pres-1"))
            .thenReturn(presentationTransaction("pres-1", completed = true))

        val response = service.initiate(
            DpaReportInitiateRequest(holderId = "holder-1", presentationTransactionId = "pres-1"),
        )

        assertEquals("pres-1", response.sourcePresentationTransactionId)
        assertEquals(listOf("WEB", "EMAIL", "PHONE"), response.availableActions.map { it.channel })
        assertTrue(response.availableActions[1].uri.contains("mailto:dpa@example.com"))
        assertNotNull(response.transactionId)
        assertEquals("rp.example.com", response.dnsName)
        assertEquals(DnsNameSource.CERTIFICATE_SAN.name, response.dnsNameSource)
        assertEquals("pres-1", response.substantiationDocument.transactionIdentifier)
    }

    /**
     * Initiates a report for a NotCompleted presentation and expects substantiation to
     * reflect that result while still offering at least one contact action.
     */
    @Test
    fun `initiate allows not completed presentations`() {
        org.mockito.Mockito.`when`(transactionLogService.get("holder-1", "pres-2"))
            .thenReturn(presentationTransaction("pres-2", completed = false))

        val response = service.initiate(
            DpaReportInitiateRequest(holderId = "holder-1", presentationTransactionId = "pres-2"),
        )

        assertEquals(Ts10TransactionResult.NotCompleted.name, response.substantiationDocument.transactionResult)
        assertTrue(response.availableActions.isNotEmpty())
    }

    /**
     * Initiates a report when the presentation has no DPA contacts configured and expects
     * empty actions, no transactionId, and a userNotice explaining the missing contact.
     */
    @Test
    fun `initiate returns empty actions when no dpa contact configured`() {
        org.mockito.Mockito.`when`(transactionLogService.get("holder-1", "pres-3"))
            .thenReturn(presentationTransaction("pres-3", completed = true, withDpa = false))

        val response = service.initiate(
            DpaReportInitiateRequest(holderId = "holder-1", presentationTransactionId = "pres-3"),
        )

        assertTrue(response.availableActions.isEmpty())
        assertNull(response.transactionId)
        assertNotNull(response.userNotice)
    }

    /**
     * Leaves the presentation without DPA contacts but configures a provider fallback DPA and
     * expects a single EMAIL action addressed to the fallback authority (CNPD).
     */
    @Test
    fun `initiate uses provider fallback when configured`() {
        properties.providerFallbackDpa = ProviderFallbackDpa().apply {
            name = "CNPD"
            country = "PT"
            email = "geral@cnpd.pt"
        }
        org.mockito.Mockito.`when`(transactionLogService.get("holder-1", "pres-4"))
            .thenReturn(presentationTransaction("pres-4", completed = true, withDpa = false))

        val response = service.initiate(
            DpaReportInitiateRequest(holderId = "holder-1", presentationTransactionId = "pres-4"),
        )

        assertEquals(1, response.availableActions.size)
        assertEquals("EMAIL", response.availableActions.first().channel)
        assertEquals("CNPD", response.dpaName)
    }

    /**
     * Mocks both Completed and NotCompleted presentation summaries for the holder and expects
     * listEligible to return both transactions as eligible report sources.
     */
    @Test
    fun `listEligible includes completed and not completed`() {
        org.mockito.Mockito.`when`(transactionLogService.list("holder-1")).thenReturn(
            listOf(
                summary("pres-1", Ts10TransactionResult.Completed),
                summary("pres-2", Ts10TransactionResult.NotCompleted),
            ),
        )
        org.mockito.Mockito.`when`(transactionLogService.get("holder-1", "pres-1"))
            .thenReturn(presentationTransaction("pres-1", completed = true))
        org.mockito.Mockito.`when`(transactionLogService.get("holder-1", "pres-2"))
            .thenReturn(presentationTransaction("pres-2", completed = false))

        val eligible = service.listEligible("holder-1")
        assertEquals(2, eligible.size)
    }

    /** Builds a TransactionLogSummary stub for the given presentation id and result. */
    private fun summary(id: String, result: Ts10TransactionResult) =
        TransactionLogSummary(
            transactionId = id,
            transactionType = Ts10TransactionType.Presentation.name,
            transactionResult = result.name,
            occurredAt = Instant.parse("2025-07-29T09:11:20Z"),
            deletedByUser = false,
        )

    /** Builds a presentation transaction with configurable completion status and optional DPA contact channels. */
    private fun presentationTransaction(
        id: String,
        completed: Boolean,
        withDpa: Boolean = true,
    ): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = id,
            time = "2025-07-29T09:11:20",
            transactionType = Ts10TransactionType.Presentation.name,
            transactionResult = if (completed) {
                Ts10TransactionResult.Completed.name
            } else {
                Ts10TransactionResult.NotCompleted.name
            },
            presentation = Ts10Presentation(
                interactingPartyIdentifier = Ts10Identifier(
                    type = "http://data.europa.eu/eudi/id/EUID",
                    identifier = "rp-1",
                ),
                interactingPartyName = "Demo RP",
                registrarURL = "https://registry.example/rp-1",
                rpDnsName = "rp.example.com",
                dpaName = if (withDpa) "DPA" else null,
                dpaCountry = if (withDpa) "PL" else null,
                dpaContact = if (withDpa) {
                    listOf("dpa@example.com", "+48111222333", "https://dpa.example/form")
                } else {
                    emptyList()
                },
                listOfClaimsRequested = listOf(
                    Ts10ClaimInfo(credentialIdentifier = "PID", claims = listOf("given_name")),
                ),
                listOfClaimsPresented = if (completed) {
                    listOf(Ts10ClaimInfo(credentialIdentifier = "PID", claims = listOf("given_name")))
                } else {
                    emptyList()
                },
                reasonOfNoncompletion = if (completed) null else "User rejected",
            ),
        )
}
