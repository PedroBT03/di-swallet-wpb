/**
 * Validation rules for relying party IDs allowed to register pseudonym passkeys.
 */

package di.swallet.wpb.pseudonym

import di.swallet.wpb.config.PseudonymProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Enforces rpId format and optional allow-list checks before pseudonym creation.
 */
@Component
class RpIdPolicy(
    private val properties: PseudonymProperties,
) {
    /**
     * Rejects invalid, malformed, or disallowed relying party IDs.
     */
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
