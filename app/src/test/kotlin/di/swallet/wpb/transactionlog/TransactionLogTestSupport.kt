package di.swallet.wpb.transactionlog

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.transactionlog.CredentialIssuerResolver
import di.swallet.wpb.datadeletion.SupportUriClassifier
import di.swallet.wpb.datadeletion.Ts10InteractingPartyContactBuilder
import di.swallet.wpb.dpareport.RpDnsNameResolver
import di.swallet.wpb.dpareport.Ts10DpaContactBuilder
import di.swallet.wpb.presentation.trust.DefaultVerifierCertificateExtractor
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
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
    private val crypto = TransactionLogCrypto(TransactionLogProperties())
    private val classifier = SupportUriClassifier()
    private val contactBuilder = Ts10InteractingPartyContactBuilder(classifier)
    private val dpaContactBuilder = Ts10DpaContactBuilder(classifier)
    private val rpDnsNameResolver = RpDnsNameResolver(DefaultVerifierCertificateExtractor())

    fun presentationTransactionMapper(): PresentationTransactionMapper =
        PresentationTransactionMapper(contactBuilder, dpaContactBuilder, rpDnsNameResolver)

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
        override fun record(holderId: String, transaction: Ts10Transaction, dedupeKey: String?): TransactionLogEntry? = null
    }
}
