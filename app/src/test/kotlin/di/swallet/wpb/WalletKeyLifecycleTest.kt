package di.swallet.wpb

import di.swallet.wpb.domain.WalletKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.*

class WalletKeyLifecycleTest : BaseIntegrationTest() {

    /**
     * Tests the generation of keys using the new dynamic authentication handshake.
     */
    @Test
    fun `should manage full key lifecycle with dynamic auth`() {
        val testUserId = "user-${UUID.randomUUID()}"

        // 1. Create Key (Requires fresh handshake)
        logger.info("Step 1: KeyLifecycle. Requesting key with dynamic challenge for $testUserId")
        val entity = HttpEntity<String>(getDynamicHeaders(testUserId))
        val createResponse = restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", entity, WalletKey::class.java)
        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.OK)

        // 2. Sign Data (Requires another fresh handshake)
        logger.info("Step 2: KeyLifecycle. Requesting signature with new dynamic challenge")
        val signRequest = mapOf("data" to "PoC Signature")
        val signEntity = HttpEntity(signRequest, getDynamicHeaders(testUserId))
        val signResponse = restTemplate.postForEntity("/api/v1/wallet/sign/$testUserId", signEntity, Map::class.java)
        
        assertThat(signResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signResponse.body?.get("signature")).isNotNull
        logger.info("Result: Lifecycle validated with dynamic authorization")
    }
}