package di.swallet.wpb.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Security interceptor that enforces the SoleControl mandate.
 * It validates that every request to the wallet API is authorized by a valid FIDO2 challenge.
 */
@Component
class AuthorizationInterceptor(private val challengeService: ChallengeService) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Intercepts incoming requests to verify the authorization header.
     * Allows anonymous access only to the authentication challenge endpoint.
     */
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Allow users to request a challenge without a token
        if (request.requestURI.contains("/auth/challenge")) {
            return true
        }

        val authHeader = request.getHeader("X-Wallet-Authorization")

        /**
         * SECURITY PLACEHOLDER:
         * Currently, we are using a "challenge echo" format: fido2-userId:challenge_value.
         * TO BE IMPLEMENTED: Replace this logic with WebAuthn assertion verification 
         * using the user's public key registered in the database.
         */
        // Validate Header Format: Expects the format "fido2-userId:challenge"
        if (authHeader != null && authHeader.startsWith("fido2-")) {
            val credentialsPart = authHeader.removePrefix("fido2-")
            val parts = credentialsPart.split(":")

            if (parts.size == 2) {
                val userId = parts[0]
                val challenge = parts[1]

                // Cryptographic Validation: Verify that the challenge is valid and belongs to the user
                if (challengeService.validateChallenge(userId, challenge)) {
                    logger.info("SecurityPolicy: Authorization successful for user $userId")
                    return true
                }
            }
        }

        // Security Breach Attempt: Block request if header is missing, malformed, or challenge is invalid
        logger.warn("SecurityPolicy: Unauthorized access attempt detected at ${request.requestURI}")
        throw UnauthorizedWalletException("Invalid or expired FIDO2 authorization context")
    }
}