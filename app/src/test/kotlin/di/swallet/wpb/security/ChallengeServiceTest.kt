package di.swallet.wpb.security

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.*
import di.swallet.wpb.config.WalletProperties

class ChallengeServiceTest {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val challengeService = ChallengeService(
        WalletProperties(challenge = WalletProperties.ChallengeProperties(ttlSeconds = 120))
    )

    /**
     * Verifies that the service correctly manages the lifecycle of a WebAuthn AssertionRequest.
     * This is essential for maintaining session context during the FIDO2 handshake.
     */
    @Test
    fun `challenge request should be single use only`() {
        val userId = "security-test-user"
        
        // Step 1: Generation - Create a minimal valid AssertionRequest context
        logger.info("Step 1: Creating a standard AssertionRequest context")
        
        // Build the inner options first
        val options = PublicKeyCredentialRequestOptions.builder()
            .challenge(ByteArray(java.security.SecureRandom().generateSeed(32)))
            .rpId("localhost")
            .build()

        // Build the top-level request object
        val dummyRequest = AssertionRequest.builder()
            .publicKeyCredentialRequestOptions(options)
            .username(Optional.of(userId))
            .build()

        challengeService.storeRequest(userId, dummyRequest)
        val rawChallenge = challengeService.getRawChallenge(userId)
        
        assertNotNull(rawChallenge)
        logger.info("Result: Challenge stored successfully: $rawChallenge")

        // Step 2: Retrieval - Verify the context is preserved
        logger.info("Step 2: Retrieving the stored request")
        val retrieved = challengeService.getRequest(userId)
        assertNotNull(retrieved)
        assertEquals(dummyRequest, retrieved)

        // Step 3: Removal - Ensure anti-replay enforcement
        logger.info("Step 3: Removing the request to prevent reuse")
        challengeService.removeRequest(userId)
        assertNull(challengeService.getRequest(userId))
        assertNull(challengeService.getRawChallenge(userId))

        logger.info("Final Result: Challenge lifecycle managed correctly for WebAuthn compliance.")
    }
}