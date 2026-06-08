package di.swallet.wpb.datadeletion

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/wallet/deletion-requests")
@Tag(name = "Data Deletion", description = "GDPR Art. 17 data erasure requests to Relying Parties (TS7 / DATA_DLT)")
class DataDeletionRequestController(
    private val service: DataDeletionRequestService,
) {
    @GetMapping("/eligible")
    @Operation(summary = "List completed presentations eligible for data deletion requests")
    fun listEligible(@RequestParam holderId: String): List<EligiblePresentation> =
        service.listEligible(holderId)

    @PostMapping
    @Operation(summary = "Initiate a data deletion request and return actionable URIs")
    fun initiate(@RequestBody request: DataDeletionInitiateRequest): DataDeletionInitiateResponse =
        service.initiate(request)
}
