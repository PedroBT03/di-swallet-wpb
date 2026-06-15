/**
 * JPA repository for encrypted transaction log entries.
 */

package di.swallet.wpb.transactionlog.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

/** Database access for [TransactionLogEntry] rows. */
@Repository
interface TransactionLogRepository : JpaRepository<TransactionLogEntry, Long> {
    /** Finds an entry by transaction ID scoped to a holder. */
    fun findByTransactionIdAndHolderId(transactionId: String, holderId: String): TransactionLogEntry?

    /** Lists active entries for a holder ordered by occurrence time descending. */
    fun findByHolderIdAndDeletedByUserFalseOrderByOccurredAtDesc(holderId: String): List<TransactionLogEntry>

    /** Lists active entries matching any of the given transaction IDs for a holder. */
    fun findByHolderIdAndTransactionIdInAndDeletedByUserFalse(
        holderId: String,
        transactionIds: Collection<String>,
    ): List<TransactionLogEntry>

    /** Counts active entries for a holder. */
    fun countByHolderIdAndDeletedByUserFalse(holderId: String): Long

    @Query(
        """
        SELECT e FROM TransactionLogEntry e
        WHERE e.holderId = :holderId
          AND e.deletedByUser = false
          AND e.occurredAt < :cutoff
        ORDER BY e.occurredAt ASC
        """,
    )
    /** Returns the oldest active entries before the retention cutoff for pruning. */
    fun findPrunable(holderId: String, cutoff: Instant): List<TransactionLogEntry>

    /** Lists all entries for a holder regardless of deletion state. */
    fun findByHolderId(holderId: String): List<TransactionLogEntry>
}
