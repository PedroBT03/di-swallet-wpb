package di.swallet.wpb.transactionlog.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface TransactionLogRepository : JpaRepository<TransactionLogEntry, Long> {
    fun findByTransactionIdAndHolderId(transactionId: String, holderId: String): TransactionLogEntry?

    fun findByHolderIdAndDeletedByUserFalseOrderByOccurredAtDesc(holderId: String): List<TransactionLogEntry>

    fun findByHolderIdAndTransactionIdInAndDeletedByUserFalse(
        holderId: String,
        transactionIds: Collection<String>,
    ): List<TransactionLogEntry>

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
    fun findPrunable(holderId: String, cutoff: Instant): List<TransactionLogEntry>

    fun findByHolderId(holderId: String): List<TransactionLogEntry>
}
