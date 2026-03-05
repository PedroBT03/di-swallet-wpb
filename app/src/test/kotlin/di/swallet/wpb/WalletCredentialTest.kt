package di.swallet.wpb

import di.swallet.wpb.domain.WalletCredential
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.*
import java.util.*

class WalletCredentialTest : BaseIntegrationTest() {

    /**
     * Validates the Format Engine's ability to issue complex SD-JWT credentials,
     * verifying the multipart format and the persistence of the issued token.
     */
    @Test
    fun `should issue SD-JWT and persist in database`() {
        val testUserId = "user-id-test-${UUID.randomUUID()}"
        val headers = createAuthHeaders()
        val emptyEntity = HttpEntity<String>(headers)

        // Create Key first
        val keyGenResponse = restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", emptyEntity, String::class.java)
        assertThat(keyGenResponse.statusCode).isEqualTo(HttpStatus.OK)

        // Issue SD-JWT
        logger.info("STEP: Issuing Selective Disclosure Credential (SD-JWT)")
        val issueResponse = restTemplate.postForEntity("/api/v1/wallet/credentials/issue-sd/$testUserId", emptyEntity, WalletCredential::class.java)
        
        assertThat(issueResponse.statusCode).isEqualTo(HttpStatus.OK)
        val encodedData = issueResponse.body?.encodedData
        assertThat(encodedData).contains("~") 
        logger.info("RESULT: SD-JWT format verified with multipart disclosures")

        // Verify Persistence (List Credentials)
        logger.info("STEP: Verifying persistence for user $testUserId")
        val listResponse = restTemplate.exchange(
            "/api/v1/wallet/credentials/$testUserId",
            HttpMethod.GET,
            emptyEntity,
            Array<WalletCredential>::class.java
        )

        assertThat(listResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(listResponse.body).isNotEmpty
        logger.info("RESULT: Credential successfully retrieved from database storage")
    }
}