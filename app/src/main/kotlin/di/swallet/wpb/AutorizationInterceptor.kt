package di.swallet.wpb

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class AuthorizationInterceptor : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val authHeader = request.getHeader("X-Wallet-Authorization")

        // In this PoC, we expect a simulated token "fido2-assertion-mock"
        // In the future (Phase 2), this is where we will validate a real FIDO2 signature.
        if (authHeader == null || authHeader != "fido2-assertion-mock") {
            logger.warn("Security: Unauthorized access attempt to ${request.requestURI}")
            throw UnauthorizedWalletException("Missing or invalid X-Wallet-Authorization header")
        }

        logger.info("Security: Valid authorization found for ${request.requestURI}")
        return true // Continue to the controller
    }
}