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
    fun currentHolderId(request: HttpServletRequest? = currentRequest()): String? =
        request?.getAttribute(WalletSecurityAttributes.AUTHENTICATED_HOLDER_ID) as? String

    /**
     * Returns the authenticated holder id or throws when no FIDO2 context is present.
     */
    fun requireCurrentHolderId(request: HttpServletRequest? = currentRequest()): String =
        currentHolderId(request)
            ?: throw UnauthorizedWalletException("Missing authenticated holder context")

    /**
     * Returns the current servlet request when running inside a web request context.
     */
    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
