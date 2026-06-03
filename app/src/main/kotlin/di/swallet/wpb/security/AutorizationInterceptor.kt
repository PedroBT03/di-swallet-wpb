package di.swallet.wpb.security

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.service.Fido2Service
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.util.Base64

/**
 * Security interceptor that enforces the Sole Control mandate.
 */
@Component
class AuthorizationInterceptor(
    private val challengeService: ChallengeService,
    private val fido2Service: Fido2Service,
    private val objectMapper: ObjectMapper
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Public revocation/status list publication endpoints consumed by external verifiers.
        if (request.method == "GET" && request.requestURI.contains("/status-lists/")) {
            return true
        }

        // Public bootstrap endpoints. Binding endpoints remain protected.
        if (
            request.requestURI.contains("/auth/challenge") ||
            request.requestURI.contains("/auth/register") ||
            request.requestURI.endsWith("/wallet/init")
        ) {
            return true
        }

        val authHeader = request.getHeader("X-Wallet-Authorization")

        // Expects "fido2-assertion:<Base64URL_JSON>"
        if (authHeader != null && authHeader.startsWith("fido2-assertion:")) {
            try {
                val encodedJson = authHeader.removePrefix("fido2-assertion:")
                val json = String(Base64.getUrlDecoder().decode(encodedJson))
                val assertionMap = objectMapper.readValue(json, Map::class.java)

                if (fido2Service.verifyStandardAssertion(
                    userId = assertionMap["userId"] as String,
                    credentialId = assertionMap["id"] as String,
                    clientDataJSON = assertionMap["clientDataJSON"] as String,
                    authenticatorData = assertionMap["authenticatorData"] as String,
                    signature = assertionMap["signature"] as String
                )) return true
            } catch (e: Exception) {
                logger.warn("SecurityPolicy: Failed to parse standard FIDO2 assertion: ${e.message}")
            }
        }

        logger.warn("SecurityPolicy: Unauthorized access attempt to ${request.requestURI}")
        throw UnauthorizedWalletException("Invalid or expired FIDO2 authorization context")
    }
}