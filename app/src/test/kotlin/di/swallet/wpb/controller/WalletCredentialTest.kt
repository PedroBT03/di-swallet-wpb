package di.swallet.wpb.controller

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.domain.CredentialBindingFormat
import di.swallet.wpb.domain.CredentialKeyBindingRepository
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.wallet.WalletTestSupport
import di.swallet.wpb.controller.WalletInitRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.*
import java.util.*

class WalletCredentialTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var credentialKeyBindingRepository: CredentialKeyBindingRepository

    /**
     * Validates the Format Engine ability to issue complex SD-JWT credentials.
     * It verifies the multipart format and the persistence of the issued token
     * under the new Dynamic Authorization policy.
     */
    @Test
    fun `should issue SD-JWT and persist in database`() {
        val testUserId = "user-id-test-${UUID.randomUUID()}"

        // Step 0: Wallet init (Phase 8 activation)
        val initEntity = HttpEntity(
            WalletInitRequest(holderId = testUserId, platform = "test", devicePubJwk = WalletTestSupport.ecPublicJwk()),
            getDynamicHeaders(testUserId),
        )
        val initResponse = restTemplate.postForEntity("/api/v1/wallet/init", initEntity, Map::class.java)
        assertThat(initResponse.statusCode).isEqualTo(HttpStatus.OK)

        // Step 1: KeyGeneration. Handshake and create hardware key
        logger.info("Step 1: KeyGeneration. Performing handshake for key creation")
        val keyGenEntity = HttpEntity<String>(getDynamicHeaders(testUserId))
        val keyGenResponse = restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", keyGenEntity, String::class.java)
        assertThat(keyGenResponse.statusCode).isEqualTo(HttpStatus.OK)

        // Step 2: CredentialIssuance. Fresh handshake to issue SD-JWT
        logger.info("Step 2: CredentialIssuance. Requesting Selective Disclosure Credential (SD-JWT)")
        val issueEntity = HttpEntity<String>(getDynamicHeaders(testUserId))
        val issueResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/issue-sd/$testUserId", 
            issueEntity, 
            WalletCredential::class.java
        )
        
        assertThat(issueResponse.statusCode).isEqualTo(HttpStatus.OK)
        val credentialId = issueResponse.body?.id!!
        val encodedData = issueResponse.body?.encodedData
        assertThat(encodedData).doesNotContain("~")
        val binding = credentialKeyBindingRepository.findByCredentialId(credentialId)
        assertThat(binding).isPresent
        assertThat(binding.get().bindingFormat).isEqualTo(CredentialBindingFormat.SD_JWT)
        logger.info("Result: Stored credential payload is minimized (signed JWT only)")

        // Step 3: PersistenceVerification. Fresh handshake to list credentials
        logger.info("Step 3: PersistenceVerification. Verifying database storage for user $testUserId")
        val listEntity = HttpEntity<String>(getDynamicHeaders(testUserId))
        val listResponse = restTemplate.exchange(
            "/api/v1/wallet/credentials/$testUserId",
            HttpMethod.GET,
            listEntity,
            Array<WalletCredential>::class.java
        )

        assertThat(listResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(listResponse.body).isNotEmpty
        logger.info("Result: Credential successfully retrieved from database storage")
    }
}