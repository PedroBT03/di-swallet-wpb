/**
 * REST API for listing eligible presentations and initiating GDPR data deletion requests.
 */

package di.swallet.wpb.datadeletion

import di.swallet.wpb.security.AuthenticatedHolderGuard
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Holder-scoped endpoints for GDPR erasure requests to relying parties. */
@RestController
@RequestMapping("/api/v1/wallet/deletion-requests")
@Tag(name = "Data Deletion", description = "GDPR data erasure requests to relying parties")
class DataDeletionRequestController(
    private val service: DataDeletionRequestService,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
) {
    /** Returns completed presentations whose claims can be requested for deletion. */
    @GetMapping("/eligible")
    @Operation(summary = "List completed presentations eligible for data deletion requests")
    fun listEligible(@RequestParam holderId: String): List<EligiblePresentation> {
        authenticatedHolderGuard.requireSelf(holderId)
        return service.listEligible(holderId)
    }

    /** Starts a deletion request and returns actionable contact URIs for the relying party. */
    @PostMapping
    @Operation(summary = "Initiate a data deletion request and return actionable URIs")
    fun initiate(@RequestBody request: DataDeletionInitiateRequest): DataDeletionInitiateResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.initiate(request)
    }
}
