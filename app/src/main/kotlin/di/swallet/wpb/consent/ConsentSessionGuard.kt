/**
 * Validates that consent operations target the authenticated session holder.
 */

package di.swallet.wpb.consent

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Ensures consent endpoints are only used by the holder who owns the session.
 */
@Component
class ConsentSessionGuard {

    /**
     * Rejects the request when the session holder and requested holderId do not match.
     */
    fun requireHolderMatch(sessionHolderId: String?, requestedHolderId: String?) {
        if (sessionHolderId.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Session has no holderId; consent requires an authenticated holder context")
        }
        if (requestedHolderId.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "holderId is required")
        }
        if (sessionHolderId != requestedHolderId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "holderId does not match session owner")
        }
    }
}
