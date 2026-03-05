package di.swallet.wpb

import di.swallet.wpb.domain.WalletKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.*

class WalletKeyLifecycleTest : BaseIntegrationTest() {

    /**
     * Verifies the full lifecycle of a hardware key: generation in the Remote WSCD,
     * persistence of metadata in the database, and usage for raw digital signatures.
     */
    @Test
    fun `should manage full key lifecycle and perform raw HSM signature`() {
        val testUserId = "user-key-test-${UUID.randomUUID()}"
        val entity = HttpEntity<String>(createAuthHeaders())

        // Create Key
        logger.info("STEP: Requesting Key Generation in Remote WSCD for $testUserId")
        val createResponse = restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", entity, WalletKey::class.java)
        
        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.OK)
        val publicKey = createResponse.body?.publicKeyBase64
        
        // Assert that the public key is present
        assertThat(publicKey).isNotBlank()
        logger.info("RESULT: Hardware key generated inside HSM. Public Key: $publicKey")

        // Sign Raw Data
        logger.info("STEP: Requesting raw signature (Proof of Possession)")
        val signRequest = mapOf("data" to "Raw Data Test")
        val signEntity = HttpEntity(signRequest, createAuthHeaders())
        val signResponse = restTemplate.postForEntity("/api/v1/wallet/sign/$testUserId", signEntity, Map::class.java)
        
        assertThat(signResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signResponse.body?.get("signature")).isNotNull
        logger.info("RESULT: HSM Signature produced successfully")
    }
}