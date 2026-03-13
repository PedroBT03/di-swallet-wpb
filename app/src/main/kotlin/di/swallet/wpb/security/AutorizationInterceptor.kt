package di.swallet.wpb.security

import di.swallet.wpb.service.Fido2Service
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Security interceptor that enforces the SoleControl mandate.
 */
@Component
class AuthorizationInterceptor(
    private val challengeService: ChallengeService,
    private val fido2Service: Fido2Service
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Allow access to endpoints used for the initial security handshake and device pairing
        if (request.requestURI.contains("/auth/challenge") || request.requestURI.contains("/auth/register")) {
            return true
        }

        val authHeader = request.getHeader("X-Wallet-Authorization")

        // Development bypass
        if (authHeader == "xxx") return true

        // Validate Header Format: Expects "fido2-userId:credentialId:signature"
        if (authHeader != null && authHeader.startsWith("fido2-")) {
            val credentialsPart = authHeader.removePrefix("fido2-")
            val parts = credentialsPart.split(":")

            if (parts.size == 3) {
                val userId = parts[0]
                val credentialId = parts[1]
                val signature = parts[2]

                // 1. Retrieve the expected challenge
                val challenge = challengeService.getChallengeForUser(userId)

                // 2. Verify the device signature via Fido2Service
                if (challenge != null && fido2Service.verifyDeviceSignature(userId, credentialId, challenge, signature)) {
                    // 3. Consume the challenge if verification succeeds
                    challengeService.validateChallenge(userId, challenge)
                    logger.info("SecurityPolicy: Authorized access for $userId via device $credentialId")
                    return true
                }
            }
        }

        logger.warn("SecurityPolicy: Unauthorized access attempt to ${request.requestURI}")
        throw UnauthorizedWalletException("Invalid or expired FIDO2 authorization context")
    }
}