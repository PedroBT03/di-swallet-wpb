package di.swallet.wpb.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class AuthenticatedHolderContext {

    fun currentHolderId(request: HttpServletRequest? = currentRequest()): String? =
        request?.getAttribute(WalletSecurityAttributes.AUTHENTICATED_HOLDER_ID) as? String

    fun requireCurrentHolderId(request: HttpServletRequest? = currentRequest()): String =
        currentHolderId(request)
            ?: throw UnauthorizedWalletException("Missing authenticated holder context")

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
