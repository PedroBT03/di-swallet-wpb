/**
 * Scheduled job that warns holders and prunes old transaction log entries when limits are exceeded.
 */

package di.swallet.wpb.transactionlog.job

import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.transactionlog.domain.TransactionLogRepository
import di.swallet.wpb.transactionlog.service.TransactionLogService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Enforces per-holder entry limits and retention windows for the transaction log. */
@Component
class TransactionLogRetentionJob(
    private val repository: TransactionLogRepository,
    private val transactionLogService: TransactionLogService,
    private val properties: TransactionLogProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val warnedHolders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Runs retention enforcement for every holder with stored transaction log entries. */
    @Scheduled(cron = "\${wpb.transaction-log.retention-cron:0 30 2 * * *}")
    fun runRetention() {
        val holderIds = repository.findAll()
            .map { it.holderId }
            .distinct()
        holderIds.forEach { holderId -> enforceRetention(holderId) }
    }

    /** Warns once when count exceeds the limit, then prunes entries older than the retention cutoff. */
    private fun enforceRetention(holderId: String) {
        val count = transactionLogService.countActive(holderId)
        val maxEntries = properties.maxEntriesPerHolder.toLong()
        if (count <= maxEntries) return

        val warningKey = "$holderId:count"
        if (warnedHolders.add(warningKey)) {
            transactionLogService.recordRetentionWarning(
                holderId,
                "Transaction log reached $count entries (limit $maxEntries). Export recommended before pruning.",
            )
        }

        val cutoff = Instant.now()
            .minus(properties.retentionGraceDays, ChronoUnit.DAYS)
            .minus(properties.retentionDays, ChronoUnit.DAYS)
        val pruned = transactionLogService.prune(holderId, cutoff)
        if (pruned > 0) {
            logger.info("Pruned {} transaction log entries for holder {}", pruned, holderId)
        }
    }
}
