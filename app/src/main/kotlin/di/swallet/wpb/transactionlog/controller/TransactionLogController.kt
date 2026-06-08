package di.swallet.wpb.transactionlog.controller

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

data class TransactionExportRequest(
    val holderId: String,
    val transactionIds: List<String> = emptyList(),
    val password: String,
)

data class MigrationExportRequest(
    val holderId: String,
    val password: String,
    val includeNonDeviceBound: Boolean = true,
)

@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Transaction Log", description = "TS10 transaction log dashboard and export")
class TransactionLogController(
    private val transactionLogService: TransactionLogService,
) {
    @GetMapping("/transactions")
    @Operation(summary = "List holder transaction log entries")
    fun list(@RequestParam holderId: String): List<TransactionLogSummary> =
        transactionLogService.list(holderId)

    @GetMapping("/transactions/{transactionId}")
    @Operation(summary = "Get a transaction log entry")
    fun get(
        @PathVariable transactionId: String,
        @RequestParam holderId: String,
    ): Ts10Transaction = transactionLogService.get(holderId, transactionId)

    @DeleteMapping("/transactions/{transactionId}")
    @Operation(summary = "Mark a transaction as deleted by user (DASH_06a)")
    fun delete(
        @PathVariable transactionId: String,
        @RequestParam holderId: String,
    ): Map<String, String> {
        transactionLogService.markDeletedByUser(holderId, transactionId)
        return mapOf("transactionId" to transactionId, "status" to "DELETED_BY_USER")
    }

    @PostMapping("/transactions/export")
    @Operation(summary = "Export selected transactions as TS10 JWE")
    fun exportTransactions(@RequestBody request: TransactionExportRequest): ResponseEntity<String> {
        val jwe = transactionLogService.exportSelected(
            holderId = request.holderId,
            transactionIds = request.transactionIds,
            password = request.password.toCharArray(),
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/jwe")
            .body(jwe)
    }

    @PostMapping("/migration/export")
    @Operation(summary = "Export TS10 Migration Object as JWE")
    fun exportMigration(@RequestBody request: MigrationExportRequest): ResponseEntity<String> {
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
