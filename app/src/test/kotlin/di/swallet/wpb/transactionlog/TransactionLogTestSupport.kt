/**
 * Shared test helpers for transaction log.
 */

package di.swallet.wpb.transactionlog

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.testTransactionLogProperties
import di.swallet.wpb.transactionlog.CredentialIssuerResolver
import di.swallet.wpb.config.DataDeletionRequestProperties
import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.datadeletion.SupportUriClassifier
import di.swallet.wpb.datadeletion.Ts10InteractingPartyContactBuilder
import di.swallet.wpb.dpareport.RpDnsNameResolver
import di.swallet.wpb.dpareport.Ts10DpaContactBuilder
import di.swallet.wpb.presentation.trust.DefaultVerifierCertificateExtractor
import di.swallet.wpb.security.HolderLogKeyContext
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
import org.mockito.Mockito
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.TransactionLogEntry
import di.swallet.wpb.transactionlog.mapper.CredentialDeletionTransactionMapper
import di.swallet.wpb.transactionlog.mapper.IssuanceTransactionMapper
import di.swallet.wpb.transactionlog.mapper.PresentationTransactionMapper
import di.swallet.wpb.transactionlog.mapper.SigningTransactionMapper
import di.swallet.wpb.transactionlog.service.TransactionLogRecorder
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import di.swallet.wpb.transactionlog.service.TransactionLogger

object TransactionLogTestSupport {
    private val holderLogKeyContext = Mockito.mock(HolderLogKeyContext::class.java)
    private val crypto = TransactionLogCrypto(testTransactionLogProperties(), holderLogKeyContext)
    private val classifier = SupportUriClassifier()
    private val contactBuilder = Ts10InteractingPartyContactBuilder(classifier)
    private val dpaContactBuilder = Ts10DpaContactBuilder(classifier)
    private val rpDnsNameResolver = RpDnsNameResolver(DefaultVerifierCertificateExtractor())

    /** Builds a presentation mapper wired with real contact classifiers and RP DNS resolution for unit tests. */
    fun presentationTransactionMapper(
        demoMode: Boolean = false,
        dataDeletion: DataDeletionRequestProperties = DataDeletionRequestProperties(),
        dpaReporting: DpaReportProperties = DpaReportProperties(),
    ): PresentationTransactionMapper {
        val openId4Vp = OpenId4VpProperties().apply { this.demoMode = demoMode }
        return PresentationTransactionMapper(
            contactBuilder,
            dpaContactBuilder,
            rpDnsNameResolver,
            openId4Vp,
            dataDeletion,
            dpaReporting,
        )
    }

    /** Returns a TransactionLogger whose recorder drops all entries but still exercises the full mapper stack. */
    fun noopTransactionLogger(): TransactionLogger =
        TransactionLogger(
            transactionLogRecorder = NoopTransactionLogRecorder(),
            presentationMapper = presentationTransactionMapper(),
            issuanceMapper = IssuanceTransactionMapper(),
            deletionMapper = CredentialDeletionTransactionMapper(CredentialIssuerResolver(ObjectMapper())),
            signingMapper = SigningTransactionMapper(crypto),
            wpbMetrics = WpbMetricsTestSupport.noop(),
        )

    private class NoopTransactionLogRecorder : TransactionLogRecorder {
        /** Discards every transaction and returns null so callers can exercise logging without persistence. */
        override fun record(holderId: String, transaction: Ts10Transaction, dedupeKey: String?): TransactionLogEntry? = null
    }
}
