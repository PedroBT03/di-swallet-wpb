package di.swallet.wpb.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ChallengeServiceTest {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val challengeService = ChallengeService()

    /**
     * Verifies the core security property of anti-replay: 
     * a challenge must be consumed (deleted) immediately after one use.
     */
    @Test
    fun `challenge should be single use only`() {
        val userId = "security-test-user"

        // Step 1: Generation - Request a fresh nonce
        logger.info("Step 1: Requesting a fresh cryptographic challenge")
        val challenge = challengeService.generateChallenge(userId)
        assertNotNull(challenge)

        // Step 2: First Validation - Initial use of the challenge
        logger.info("Step 2: Attempting first use of the challenge")
        val firstAttempt = challengeService.validateChallenge(userId, challenge)
        assertTrue(firstAttempt)
        logger.info("Result: First validation successful")

        // Step 3: Second Validation - Anti-replay enforcement
        logger.info("Step 3: Attempting second use of the same challenge")
        val secondAttempt = challengeService.validateChallenge(userId, challenge)
        assertFalse(secondAttempt)

        logger.info("Final Result: Anti-replay policy enforced. Challenge was successfully consumed.")
    }
}