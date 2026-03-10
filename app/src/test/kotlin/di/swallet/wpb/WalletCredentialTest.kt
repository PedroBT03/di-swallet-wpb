package di.swallet.wpb

import di.swallet.wpb.domain.WalletCredential
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.*
import java.util.*

class WalletCredentialTest : BaseIntegrationTest() {

    /**
     * Validates the Format Engine ability to issue complex SD-JWT credentials.
     * It verifies the multipart format and the persistence of the issued token
     * under the new Dynamic Authorization policy.
     */
    @Test
    fun `should issue SD-JWT and persist in database`() {
        val testUserId = "user-id-test-${UUID.randomUUID()}"

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
        val encodedData = issueResponse.body?.encodedData
        assertThat(encodedData).contains("~") 
        logger.info("Result: SD-JWT format verified with multipart disclosures")

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