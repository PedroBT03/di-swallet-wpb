/**
 * Tests wallet key lifecycle.
 */

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
     * Creates a wallet key with FIDO2 headers, signs sample data with a fresh challenge,
     * and expects both operations to return 200 with a non-null signature.
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
     * Creates a key, revokes it, ensures a replacement key, then signs successfully again.
     */
    @Test
    fun `should rotate revoked key when ensure is called again`() {
        val userId = "rotate-user-${UUID.randomUUID()}"
        val authEntity = { HttpEntity<String>(getDynamicHeaders(userId)) }

        val firstKey = restTemplate.postForEntity(
            "/api/v1/wallet/keys/$userId",
            authEntity(),
            WalletKey::class.java,
        )
        assertThat(firstKey.statusCode).isEqualTo(HttpStatus.OK)
        val revokedAlias = firstKey.body?.keyAlias

        restTemplate.postForEntity(
            "/api/v1/wallet/keys/$userId/revoke",
            authEntity(),
            Map::class.java,
        )

        val signBlocked = restTemplate.postForEntity(
            "/api/v1/wallet/sign/$userId",
            HttpEntity(mapOf("data" to "blocked"), getDynamicHeaders(userId)),
            Map::class.java,
        )
        assertThat(signBlocked.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val rotatedKey = restTemplate.postForEntity(
            "/api/v1/wallet/keys/$userId",
            authEntity(),
            WalletKey::class.java,
        )
        assertThat(rotatedKey.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(rotatedKey.body?.keyAlias).isNotEqualTo(revokedAlias)

        val signOk = restTemplate.postForEntity(
            "/api/v1/wallet/sign/$userId",
            HttpEntity(mapOf("data" to "allowed"), getDynamicHeaders(userId)),
            Map::class.java,
        )
        assertThat(signOk.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signOk.body?.get("signature")).isNotNull
    }

    /**
     * Creates a key, revokes it via the status-list bitstring, then attempts to sign and
     * expects HTTP 403 because the revoked bit blocks further HSM use.
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
