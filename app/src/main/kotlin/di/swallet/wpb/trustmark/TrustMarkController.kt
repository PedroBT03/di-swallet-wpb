/**
 * REST API for viewing the EUDI Wallet Trust Mark and refreshing cached resources.
 */

package di.swallet.wpb.trustmark

import di.swallet.wpb.config.TrustMarkProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Public Trust Mark view endpoints and optional admin cache refresh. */
@RestController
@RequestMapping("/api/v1/wallet/trust-mark")
@Tag(name = "Trust Mark", description = "EUDI Wallet Trust Mark view")
class TrustMarkController(
    private val service: TrustMarkService,
    private val properties: TrustMarkProperties,
) {
    /** Returns the localized Trust Mark view for wallet solution certification. */
    @GetMapping
    @Operation(summary = "Get Trust Mark view for wallet solution certification (public)")
    fun getView(@RequestParam(required = false) lang: String?): TrustMarkView =
        service.getView(lang)

    /** Clears and refetches the cached TrustMarkResource when admin refresh is enabled. */
    @PostMapping("/refresh")
    @Operation(summary = "Invalidate and refresh cached TrustMarkResource (admin/dev)")
    fun refresh(): TrustMarkView {
        if (!properties.allowAdminRefresh) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Trust Mark cache refresh is disabled. Set wpb.trust-mark.allow-admin-refresh=true for dev.",
            )
        }
        return service.refresh()
    }
}
