package di.swallet.wpb.security

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.config.TrustMarkProperties
import di.swallet.wpb.ops.metrics.WpbMetrics
import di.swallet.wpb.service.Fido2Service
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.HandlerInterceptor
import java.util.Base64

/**
 * Security interceptor that enforces the Sole Control mandate.
 */
@Component
class AuthorizationInterceptor(
    private val challengeService: ChallengeService,
    private val fido2Service: Fido2Service,
    private val objectMapper: ObjectMapper,
    private val trustMarkProperties: TrustMarkProperties,
    private val wpbMetrics: WpbMetrics,
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

        // TS1 Trust Mark metadata is wallet-solution level and contains no holder data.
        if (request.method == "GET" && request.requestURI.endsWith("/wallet/trust-mark")) {
            return true
        }
        if (
            request.method == "POST" &&
            request.requestURI.endsWith("/wallet/trust-mark/refresh") &&
            trustMarkProperties.allowAdminRefresh
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
                val userId = assertionMap["userId"] as? String

                if (userId.isNullOrBlank()) {
                    wpbMetrics.recordFido2Failure("missing_user_id")
                } else if (fido2Service.verifyStandardAssertion(
                    userId = userId,
                    credentialId = assertionMap["id"] as String,
                    clientDataJSON = assertionMap["clientDataJSON"] as String,
                    authenticatorData = assertionMap["authenticatorData"] as String,
                    signature = assertionMap["signature"] as String
                )) {
                    request.setAttribute(WalletSecurityAttributes.AUTHENTICATED_HOLDER_ID, userId)
                    enforceRequestedHolderBinding(request, userId)
                    return true
                } else {
                    wpbMetrics.recordFido2Failure("verification_failed")
                }
            } catch (e: ResponseStatusException) {
                throw e
            } catch (e: Exception) {
                logger.warn("SecurityPolicy: Failed to parse standard FIDO2 assertion: ${e.message}")
                wpbMetrics.recordFido2Failure("parse_error")
            }
        } else {
            wpbMetrics.recordFido2Failure("missing_or_invalid")
        }

        logger.warn("SecurityPolicy: Unauthorized access attempt to ${request.requestURI}")
        throw UnauthorizedWalletException("Invalid or expired FIDO2 authorization context")
    }

    private fun enforceRequestedHolderBinding(request: HttpServletRequest, authenticatedHolderId: String) {
        val queryHolderId = request.getParameter("holderId")?.takeIf { it.isNotBlank() }
        if (queryHolderId != null && queryHolderId != authenticatedHolderId) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "holderId does not match authenticated user",
            )
        }

        val pathHolderId = WalletPathHolderExtractor.extractHolderId(request.requestURI, request.method)
        if (pathHolderId != null && pathHolderId != authenticatedHolderId) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Resource holder does not match authenticated user",
            )
        }
    }
}