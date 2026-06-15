package di.swallet.wpb.transactionlog.service

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
import di.swallet.wpb.transactionlog.crypto.Ts10JweEncoder
import di.swallet.wpb.transactionlog.domain.TransactionLogEntry
import di.swallet.wpb.transactionlog.domain.TransactionLogRepository
import di.swallet.wpb.transactionlog.domain.Ts10MigrationData
import di.swallet.wpb.transactionlog.domain.Ts10OtherTransaction
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionLogExport
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.Ts10InstantFormatter
import di.swallet.wpb.transactionlog.export.MigrationObjectBuilder
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class TransactionLogSummary(
    val transactionId: String,
    val transactionType: String,
    val transactionResult: String,
    val occurredAt: Instant,
    val deletedByUser: Boolean,
)

@Service
class TransactionLogService(
    private val repository: TransactionLogRepository,
    private val crypto: TransactionLogCrypto,
    private val objectMapper: ObjectMapper,
    private val properties: TransactionLogProperties,
    private val jweEncoder: Ts10JweEncoder,
    @Lazy private val migrationObjectBuilder: MigrationObjectBuilder,
) : TransactionLogRecorder {
    private val loggedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Transactional
    override fun record(holderId: String, transaction: Ts10Transaction, dedupeKey: String?): TransactionLogEntry? {
        if (holderId.isBlank()) return null
        if (dedupeKey != null && !loggedSessions.add(dedupeKey)) return null

        val payloadJson = objectMapper.writeValueAsBytes(transaction)
        val ciphertext = crypto.encrypt(holderId, payloadJson)
        val occurredAt = Instant.now()
        val transactionId = transaction.transactionIdentifier

        val mac = crypto.integrityMac(
            transactionId = transactionId,
            holderId = holderId,
            occurredAtEpochMillis = occurredAt.toEpochMilli(),
            transactionType = transaction.transactionType,
            transactionResult = transaction.transactionResult,
            payloadCiphertext = ciphertext,
        )

        val entry = TransactionLogEntry(
            holderId = holderId,
            transactionId = transactionId,
            transactionType = transaction.transactionType,
            transactionResult = transaction.transactionResult,
            occurredAt = occurredAt,
            ts10SchemaVersion = properties.ts10SchemaVersion,
            payloadCiphertext = ciphertext,
            integrityMac = mac,
        )
        return repository.save(entry)
    }

    fun list(holderId: String): List<TransactionLogSummary> =
        repository.findByHolderIdAndDeletedByUserFalseOrderByOccurredAtDesc(holderId)
            .map { entry ->
                TransactionLogSummary(
                    transactionId = entry.transactionId,
                    transactionType = entry.transactionType,
                    transactionResult = entry.transactionResult,
                    occurredAt = entry.occurredAt,
                    deletedByUser = entry.deletedByUser,
                )
            }

    fun get(holderId: String, transactionId: String): Ts10Transaction {
        val entry = repository.findByTransactionIdAndHolderId(transactionId, holderId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction $transactionId not found")
        if (entry.deletedByUser) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction $transactionId was deleted by user")
        }
        return decodeEntry(holderId, entry)
    }

    @Transactional
    fun markDeletedByUser(holderId: String, transactionId: String) {
        val entry = repository.findByTransactionIdAndHolderId(transactionId, holderId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction $transactionId not found")
        entry.deletedByUser = true
        repository.save(entry)
    }

    fun exportSelected(holderId: String, transactionIds: List<String>, password: CharArray): String {
        val entries = if (transactionIds.isEmpty()) {
            repository.findByHolderIdAndDeletedByUserFalseOrderByOccurredAtDesc(holderId)
        } else {
            repository.findByHolderIdAndTransactionIdInAndDeletedByUserFalse(holderId, transactionIds)
        }
        if (entries.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No transactions available for export")
        }
        val transactions = entries.map { decodeEntry(holderId, it) }
        val export = Ts10TransactionLogExport(transactionLog = transactions)
        return jweEncoder.encryptTransactionLogExport(export, password)
    }

    fun exportMigration(holderId: String, password: CharArray, includeNonDeviceBound: Boolean): String {
        val migration = migrationObjectBuilder.build(holderId, includeNonDeviceBound)
        return jweEncoder.encryptMigrationData(migration, password)
    }

    @Transactional
    fun recordRetentionWarning(holderId: String, message: String): TransactionLogEntry? {
        val transaction = Ts10Transaction(
            transactionIdentifier = java.util.UUID.randomUUID().toString(),
            time = Ts10InstantFormatter.format(Instant.now()),
            transactionType = Ts10TransactionType.OtherTransaction.name,
            transactionResult = "Completed",
            otherTransaction = Ts10OtherTransaction(description = message),
        )
        return record(holderId, transaction)
    }

    @Transactional
    fun prune(holderId: String, cutoff: Instant): Int {
        val prunable = repository.findPrunable(holderId, cutoff)
        if (prunable.isEmpty()) return 0
        repository.deleteAll(prunable)
        return prunable.size
    }

    fun countActive(holderId: String): Long = repository.countByHolderIdAndDeletedByUserFalse(holderId)

    private fun decodeEntry(holderId: String, entry: TransactionLogEntry): Ts10Transaction {
        verifyIntegrity(holderId, entry)
        val plaintext = crypto.decrypt(holderId, entry.payloadCiphertext)
        return objectMapper.readValue(plaintext, Ts10Transaction::class.java)
    }

    private fun verifyIntegrity(holderId: String, entry: TransactionLogEntry) {
        val expected = crypto.integrityMac(
            transactionId = entry.transactionId,
            holderId = holderId,
            occurredAtEpochMillis = entry.occurredAt.toEpochMilli(),
            transactionType = entry.transactionType,
            transactionResult = entry.transactionResult,
            payloadCiphertext = entry.payloadCiphertext,
        )
        if (expected != entry.integrityMac) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Transaction log integrity check failed for ${entry.transactionId}")
        }
    }
}
