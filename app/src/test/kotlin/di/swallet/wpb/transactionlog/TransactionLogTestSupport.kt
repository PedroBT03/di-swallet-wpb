package di.swallet.wpb.transactionlog

import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.datadeletion.SupportUriClassifier
import di.swallet.wpb.datadeletion.Ts10InteractingPartyContactBuilder
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.TransactionLogEntry
import di.swallet.wpb.transactionlog.mapper.CredentialDeletionTransactionMapper
import di.swallet.wpb.transactionlog.mapper.IssuanceTransactionMapper
import di.swallet.wpb.transactionlog.mapper.PresentationTransactionMapper
import di.swallet.wpb.transactionlog.mapper.SigningTransactionMapper
import di.swallet.wpb.transactionlog.service.TransactionLogRecorder
import di.swallet.wpb.transactionlog.service.TransactionLogger

object TransactionLogTestSupport {
    private val crypto = TransactionLogCrypto(TransactionLogProperties())
    private val contactBuilder = Ts10InteractingPartyContactBuilder(SupportUriClassifier())

    fun presentationTransactionMapper(): PresentationTransactionMapper =
        PresentationTransactionMapper(contactBuilder)

    fun noopTransactionLogger(): TransactionLogger =
        TransactionLogger(
            transactionLogRecorder = NoopTransactionLogRecorder(),
            presentationMapper = presentationTransactionMapper(),
            issuanceMapper = IssuanceTransactionMapper(),
            deletionMapper = CredentialDeletionTransactionMapper(),
            signingMapper = SigningTransactionMapper(crypto),
        )

    private class NoopTransactionLogRecorder : TransactionLogRecorder {
        override fun record(holderId: String, transaction: Ts10Transaction, dedupeKey: String?): TransactionLogEntry? = null
    }
}
