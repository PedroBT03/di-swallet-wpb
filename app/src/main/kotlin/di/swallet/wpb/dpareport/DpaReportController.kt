package di.swallet.wpb.dpareport

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/wallet/dpa-reports")
@Tag(name = "DPA Reporting", description = "Report suspicious WRP requests to DPAs (TS8 / RPT_DPA)")
class DpaReportController(
    private val service: DpaReportService,
) {
    @GetMapping("/eligible")
    @Operation(summary = "List presentation transactions eligible for DPA reporting")
    fun listEligible(@RequestParam holderId: String): List<EligibleDpaReportPresentation> =
        service.listEligible(holderId)

    @PostMapping
    @Operation(summary = "Initiate a DPA report and return actionable URIs plus substantiation")
    fun initiate(@RequestBody request: DpaReportInitiateRequest): DpaReportInitiateResponse =
        service.initiate(request)
}
