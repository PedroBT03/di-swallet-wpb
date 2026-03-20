package di.swallet.wpb.controller

import di.swallet.wpb.service.StatusListService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Public endpoints for revocation status publication.
 *
 * These endpoints are intentionally read-only and unauthenticated to enable
 * relying parties and external ecosystem services to retrieve revocation state.
 */
@RestController
@RequestMapping("/api/v1/wallet/status-lists")
@Tag(name = "Status List Publication", description = "Public revocation status list endpoints")
class StatusListController(
    private val statusListService: StatusListService
) {

    @GetMapping("/{listId}")
    @Operation(
        summary = "Get published revocation list",
        description = "Returns an interoperable bitstring status list payload for external verifiers"
    )
    fun getPublishedStatusList(
        @PathVariable listId: String,
        request: HttpServletRequest
    ): Map<String, Any> {
        val canonicalId = statusListService.getListId()
        if (listId != canonicalId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown status list id: $listId")
        }

        val baseUrl = request.requestURL.toString()

        return mapOf(
            "id" to baseUrl,
            "type" to "BitstringStatusList",
            "statusPurpose" to "revocation",
            "encodedList" to statusListService.getEncodedStatusList(),
            "nextIndex" to statusListService.getCurrentNextIndex(),
            "issuedAt" to Instant.now().toString()
        )
    }

    @GetMapping("/{listId}/entries/{index}")
    @Operation(
        summary = "Check revocation entry",
        description = "Returns the current status (ACTIVE/REVOKED) of one status list bit index"
    )
    fun getStatusEntry(
        @PathVariable listId: String,
        @PathVariable index: Int
    ): Map<String, Any> {
        val canonicalId = statusListService.getListId()
        if (listId != canonicalId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown status list id: $listId")
        }
        if (index < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Index must be non-negative")
        }

        val revoked = statusListService.isRevoked(index)

        return mapOf(
            "listId" to canonicalId,
            "index" to index,
            "statusPurpose" to "revocation",
            "status" to if (revoked) "REVOKED" else "ACTIVE",
            "revoked" to revoked
        )
    }
}
