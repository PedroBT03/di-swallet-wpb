package di.swallet.wpb.controller

import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.ops.metrics.WpbMetrics
import di.swallet.wpb.revocation.StatusListJwtEncoder
import di.swallet.wpb.service.StatusListService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
    private val statusListService: StatusListService,
    private val statusListJwtEncoder: StatusListJwtEncoder,
    private val properties: StatusListProperties,
    private val wpbMetrics: WpbMetrics,
) {

    @GetMapping("/{listId}")
    @Operation(
        summary = "Get published revocation list",
        description = "Returns a Token Status List JWT (default) or legacy JSON bitstring payload",
    )
    fun getPublishedStatusList(
        @PathVariable listId: String,
        @RequestParam(required = false, defaultValue = "jwt") format: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> = wpbMetrics.timeStatusList {
        val canonicalId = statusListService.getListId()
        if (listId != canonicalId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown status list id: $listId")
        }

        if (format.equals("json", ignoreCase = true)) {
            val baseUrl = request.requestURL.toString()
            return@timeStatusList ResponseEntity.ok(
                mapOf(
                    "id" to baseUrl,
                    "type" to "BitstringStatusList",
                    "statusPurpose" to "revocation",
                    "encodedList" to statusListService.getEncodedStatusList(),
                    "capacity" to statusListService.getCapacity(),
                    "allocated" to statusListService.getAllocatedCount(),
                    "issuedAt" to Instant.now().toString(),
                ),
            )
        }

        val jwt = statusListJwtEncoder.encode()
        ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=${properties.jwtTtlSeconds}")
            .contentType(MediaType.parseMediaType("application/statuslist+jwt"))
            .body(jwt)
    }

    @GetMapping("/{listId}/entries/{index}")
    @Operation(
        summary = "Check revocation entry",
        description = "Returns the current status (ACTIVE/REVOKED) of one status list bit index",
    )
    fun getStatusEntry(
        @PathVariable listId: String,
        @PathVariable index: Int,
    ): Map<String, Any> = wpbMetrics.timeStatusList {
        val canonicalId = statusListService.getListId()
        if (listId != canonicalId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown status list id: $listId")
        }
        if (index < 0 || index >= statusListService.getCapacity()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Index out of range")
        }

        val revoked = statusListService.isRevoked(index)

        mapOf(
            "listId" to canonicalId,
            "index" to index,
            "statusPurpose" to "revocation",
            "status" to if (revoked) "REVOKED" else "ACTIVE",
            "revoked" to revoked,
        )
    }
}
