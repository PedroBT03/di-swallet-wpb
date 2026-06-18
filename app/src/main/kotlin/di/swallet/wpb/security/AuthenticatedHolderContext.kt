/**
 * Request-scoped access to the holder id established by FIDO2 authentication.
 */

package di.swallet.wpb.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Reads and requires the authenticated holder id stored on the current HTTP request.
 */
@Component
class AuthenticatedHolderContext {

    /**
     * Returns the authenticated holder id from the request, if FIDO2 auth succeeded.
     */
    fun currentHolderId(explicitRequest: HttpServletRequest? = null): String? {
        AuthenticatedHolderRequestBinder.currentHolderId()?.let { return it }
        for (candidate in requestCandidates(explicitRequest)) {
            val holderId = candidate.getAttribute(WalletSecurityAttributes.AUTHENTICATED_HOLDER_ID) as? String
            if (!holderId.isNullOrBlank()) {
                return holderId
            }
        }
        return null
    }

    /**
     * Returns the authenticated holder id or throws when no FIDO2 context is present.
     */
    fun requireCurrentHolderId(explicitRequest: HttpServletRequest? = null): String =
        currentHolderId(explicitRequest)
            ?: throw UnauthorizedWalletException("Missing authenticated holder context")

    /**
     * Tries every servlet request object that may represent the active HTTP call.
     */
    private fun requestCandidates(explicitRequest: HttpServletRequest?): List<HttpServletRequest> {
        val candidates = linkedSetOf<HttpServletRequest>()
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.let { candidates.add(it) }
        explicitRequest?.let { candidates.add(it) }
        return candidates.toList()
    }
}
