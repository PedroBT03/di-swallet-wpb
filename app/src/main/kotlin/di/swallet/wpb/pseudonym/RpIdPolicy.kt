package di.swallet.wpb.pseudonym

import di.swallet.wpb.config.PseudonymProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * MVP RP identity gate (PA_20 baseline). Allow-list is a local mitigation only;
 * it does not replace TLS/browser RP verification or access-certificate trust.
 */
@Component
class RpIdPolicy(
    private val properties: PseudonymProperties,
) {
    fun validateRpId(rpId: String) {
        val normalized = rpId.trim().lowercase()
        if (normalized.isBlank() || normalized.length > 256) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid rpId")
        }
        if (normalized.contains("://") || normalized.contains("/")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "rpId must be a registrable domain suffix")
        }
        val allowed = properties.allowedRpIdSet()
        if (allowed.isNotEmpty() && normalized !in allowed) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "rpId is not in the allowed list")
        }
    }
}
