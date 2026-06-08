package di.swallet.wpb.dpareport

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.ProviderFallbackDpa
import di.swallet.wpb.datadeletion.SupportUriClassifier
import di.swallet.wpb.presentation.registry.RpRegistryResolver
import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import di.swallet.wpb.transactionlog.domain.Ts10DpaReport
import di.swallet.wpb.transactionlog.domain.Ts10Identifier
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.service.TransactionLogService
import di.swallet.wpb.transactionlog.service.TransactionLogger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class DpaReportPrivacyTest {
    private val properties = DpaReportProperties().apply {
        providerFallbackDpa = ProviderFallbackDpa().apply {
            name = "DPA"
            country = "PL"
            email = "dpa@example.com"
        }
    }

    @Test
    fun `substantiation and dpa report contain claim paths only`() {
        val transactionLogService = mock(TransactionLogService::class.java)
        val registryResolver = mock(RpRegistryResolver::class.java)
        val transactionLogger = mock(TransactionLogger::class.java)
        val classifier = SupportUriClassifier()

        org.mockito.Mockito.`when`(transactionLogService.get("holder-1", "pres-1"))
            .thenReturn(
                Ts10Transaction(
                    transactionIdentifier = "pres-1",
                    time = "2025-07-29T09:11:20",
                    transactionType = Ts10TransactionType.Presentation.name,
                    transactionResult = Ts10TransactionResult.Completed.name,
                    presentation = Ts10Presentation(
                        interactingPartyIdentifier = Ts10Identifier(
                            type = "http://data.europa.eu/eudi/id/EUID",
                            identifier = "rp-1",
                        ),
                        interactingPartyName = "Demo RP",
                        registrarURL = "https://registry.example/rp-1",
                        dpaContact = listOf("dpa@example.com"),
                        listOfClaimsPresented = listOf(
                            Ts10ClaimInfo(credentialIdentifier = "PID", claims = listOf("given_name")),
                        ),
                    ),
                ),
            )

        val service = DpaReportService(
            transactionLogService = transactionLogService,
            contactResolver = DpaContactResolver(
                transactionLogService = transactionLogService,
                dpaContactBuilder = Ts10DpaContactBuilder(classifier),
                registryResolver = registryResolver,
                rpDnsNameResolver = RpDnsNameResolver(
                    di.swallet.wpb.presentation.trust.DefaultVerifierCertificateExtractor(),
                ),
                properties = properties,
            ),
            actionBuilder = DpaActionBuilder(DpaMailTemplateBuilder(properties)),
            mapper = DpaReportMapper(),
            transactionLogger = transactionLogger,
        )

        val response = service.initiate(
            DpaReportInitiateRequest(holderId = "holder-1", presentationTransactionId = "pres-1"),
        )

        val serialized = response.toString() + response.substantiationDocument.toString()
        assertFalse(serialized.contains("Pedro"))
        assertFalse(serialized.contains("eyJ"))
        assertFalse(serialized.contains("sd_jwt"))

        val dpaReport = Ts10DpaReport(
            dpaName = response.dpaName,
            dpaCountry = response.dpaCountry,
            reportChannel = response.availableActions.firstOrNull()?.channel,
            reportContact = "dpa@example.com",
        )
        val dpaSerialized = dpaReport.toString()
        assertFalse(dpaSerialized.contains("given_name"))
    }
}
