package di.swallet.wpb.transactionlog.service

import di.swallet.wpb.transactionlog.domain.TransactionLogEntry
import di.swallet.wpb.transactionlog.domain.Ts10Transaction

interface TransactionLogRecorder {
    fun record(holderId: String, transaction: Ts10Transaction, dedupeKey: String? = null): TransactionLogEntry?
}
