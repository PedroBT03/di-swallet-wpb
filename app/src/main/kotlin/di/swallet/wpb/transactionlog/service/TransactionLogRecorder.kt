/**
 * Contract for persisting TS10 transactions to the encrypted transaction log.
 */

package di.swallet.wpb.transactionlog.service

import di.swallet.wpb.transactionlog.domain.TransactionLogEntry
import di.swallet.wpb.transactionlog.domain.Ts10Transaction

/** Abstraction for recording wallet transaction log entries. */
interface TransactionLogRecorder {
    /** Persists a transaction for a holder. Optional dedupe key prevents duplicate writes. */
    fun record(holderId: String, transaction: Ts10Transaction, dedupeKey: String? = null): TransactionLogEntry?
}
