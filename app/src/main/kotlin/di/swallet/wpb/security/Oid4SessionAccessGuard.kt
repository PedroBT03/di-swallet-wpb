/**
 * Access guard for OpenID4VP and OpenID4VCI session endpoints.
 */

package di.swallet.wpb.security

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Ensures OID4 session reads are bound to the FIDO2-authenticated holder.
 */
@Component
class Oid4SessionAccessGuard(
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
) {
    /**
     * Rejects the call when the session holder is missing or not the authenticated user.
     */
    fun requireSessionHolder(holderId: String?) {
        if (holderId.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "OID4 session is not bound to a holder")
        }
        authenticatedHolderGuard.requireSelf(holderId)
    }
}
