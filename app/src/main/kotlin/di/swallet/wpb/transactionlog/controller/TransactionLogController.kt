/**
 * REST API for listing, reading, deleting, and exporting wallet transaction log entries.
 */

package di.swallet.wpb.transactionlog.controller

import di.swallet.wpb.security.AuthenticatedHolderGuard
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.service.TransactionLogService
import di.swallet.wpb.transactionlog.service.TransactionLogSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Request body for exporting selected transactions as JWE. */
data class TransactionExportRequest(
    val holderId: String,
    val transactionIds: List<String> = emptyList(),
    val password: String,
)

/** Request body for exporting migration data as JWE. */
data class MigrationExportRequest(
    val holderId: String,
    val password: String,
    val includeNonDeviceBound: Boolean = true,
)

/** Holder-scoped transaction log dashboard and export endpoints. */
@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Transaction Log", description = "Transaction log dashboard and export")
class TransactionLogController(
    private val transactionLogService: TransactionLogService,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
) {
    /** Lists transaction summaries for the authenticated holder. */
    @GetMapping("/transactions")
    @Operation(summary = "List holder transaction log entries")
    fun list(@RequestParam holderId: String): List<TransactionLogSummary> {
        authenticatedHolderGuard.requireSelf(holderId)
        return transactionLogService.list(holderId)
    }

    /** Returns the decrypted TS10 transaction for a single entry. */
    @GetMapping("/transactions/{transactionId}")
    @Operation(summary = "Get a transaction log entry")
    fun get(
        @PathVariable transactionId: String,
        @RequestParam holderId: String,
    ): Ts10Transaction {
        authenticatedHolderGuard.requireSelf(holderId)
        return transactionLogService.get(holderId, transactionId)
    }

    /** Marks a transaction as deleted by the user without erasing the stored row. */
    @DeleteMapping("/transactions/{transactionId}")
    @Operation(summary = "Mark a transaction as deleted by user (DASH_06a)")
    fun delete(
        @PathVariable transactionId: String,
        @RequestParam holderId: String,
    ): Map<String, String> {
        authenticatedHolderGuard.requireSelf(holderId)
        transactionLogService.markDeletedByUser(holderId, transactionId)
        return mapOf("transactionId" to transactionId, "status" to "DELETED_BY_USER")
    }

    /** Exports transactions as a password-protected JWE with content type application/jwe. */
    @PostMapping("/transactions/export")
    @Operation(summary = "Export selected transactions as JWE")
    fun exportTransactions(@RequestBody request: TransactionExportRequest): ResponseEntity<String> {
        authenticatedHolderGuard.requireSelf(request.holderId)
        val jwe = transactionLogService.exportSelected(
            holderId = request.holderId,
            transactionIds = request.transactionIds,
            password = request.password.toCharArray(),
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/jwe")
            .body(jwe)
    }

    /** Exports migration object (log plus credentials) as a password-protected JWE. */
    @PostMapping("/migration/export")
    @Operation(summary = "Export migration object as JWE")
    fun exportMigration(@RequestBody request: MigrationExportRequest): ResponseEntity<String> {
        authenticatedHolderGuard.requireSelf(request.holderId)
        val jwe = transactionLogService.exportMigration(
            holderId = request.holderId,
            password = request.password.toCharArray(),
            includeNonDeviceBound = request.includeNonDeviceBound,
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/jwe")
            .body(jwe)
    }
}
