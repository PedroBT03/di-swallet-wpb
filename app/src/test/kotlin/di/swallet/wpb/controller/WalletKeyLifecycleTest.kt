package di.swallet.wpb.controller

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.domain.WalletKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.*

class WalletKeyLifecycleTest : BaseIntegrationTest() {

    /**
     * Tests the generation of keys using the dynamic authentication handshake.
     */
    @Test
    fun `should manage full key lifecycle with dynamic auth`() {
        val testUserId = "user-${UUID.randomUUID()}"

        // Step 1: Initialization - Handshake and key generation
        logger.info("Step 1: Initializing hardware key with dynamic challenge for $testUserId")
        val entity = HttpEntity<String>(getDynamicHeaders(testUserId))
        val createResponse = restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", entity, WalletKey::class.java)
        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.OK)

        // Step 2: Signing - Requesting signature with a new challenge
        logger.info("Step 2: Requesting digital signature using a new dynamic challenge")
        val signRequest = mapOf("data" to "PoC Signature")
        val signEntity = HttpEntity(signRequest, getDynamicHeaders(testUserId))
        val signResponse = restTemplate.postForEntity("/api/v1/wallet/sign/$testUserId", signEntity, Map::class.java)
        
        assertThat(signResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signResponse.body?.get("signature")).isNotNull
        logger.info("Final Result: Key lifecycle validated under dynamic authorization.")
    }

    /**
     * Security test to verify the enforcement of the revocation bitstring.
     */
    @Test
    fun `should block signature when key is revoked in bitstring`() {
        val userId = "revoked-user-${UUID.randomUUID()}"

        // Step 1: Initialization - Setup hardware key
        logger.info("Step 1: Setting up hardware key for $userId")
        restTemplate.postForEntity("/api/v1/wallet/keys/$userId", HttpEntity<String>(getDynamicHeaders(userId)), WalletKey::class.java)

        // Step 2: Revocation - Set bit to 1 in the Status List
        logger.info("Step 2: Revoking key in the global status list bitstring")
        restTemplate.postForEntity("/api/v1/wallet/keys/$userId/revoke", HttpEntity<String>(getDynamicHeaders(userId)), Map::class.java)

        // Step 3: Enforcement Check - Attempt to use the revoked key
        logger.info("Step 3: Attempting to sign data with the revoked key")
        val signRequest = mapOf("data" to "Unauthorized Transaction")
        val signResponse = restTemplate.postForEntity(
            "/api/v1/wallet/sign/$userId", 
            HttpEntity(signRequest, getDynamicHeaders(userId)), 
            Map::class.java
        )

        // Step 4: Final Assertions
        assertThat(signResponse.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        logger.info("Final Result: Access denied as expected. Revocation policy enforced via bitstring.")
    }
}