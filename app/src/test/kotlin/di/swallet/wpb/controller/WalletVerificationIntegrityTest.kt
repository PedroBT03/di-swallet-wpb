/**
 * Tests wallet verification integrity.
 */

package di.swallet.wpb.controller

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.controller.PresentationRequest
import di.swallet.wpb.controller.VerificationRequest
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.wallet.WalletTestSupport
import di.swallet.wpb.controller.WalletInitRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.*

class WalletVerificationIntegrityTest : BaseIntegrationTest() {

    /**
     * Runs wallet init, key creation, SD-JWT issuance, selective presentation of nationalities
     * only, and mock-RP verification, expecting verified claims to include nationalities but not given_name.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `should perform full selective disclosure with dynamic auth`() {
        val testUserId = "integrity-user-${UUID.randomUUID()}"
        
        // Step 0: Wallet init
        restTemplate.postForEntity(
            "/api/v1/wallet/init",
            HttpEntity(
                WalletInitRequest(holderId = testUserId, platform = "test", devicePubJwk = WalletTestSupport.ecPublicJwk()),
                getDynamicHeaders(testUserId),
            ),
            Map::class.java,
        )

        // Step 1: Initialization. Setup hardware key
        logger.info("Step 1: Initialization. Handshake and key generation")
        restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", HttpEntity<String>(getDynamicHeaders(testUserId)), WalletKey::class.java)

        // Step 2: Issuance. Receive full credential
        logger.info("Step 2: Issuance. Handshake and issuance")
        val issueResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/issue-sd/$testUserId", 
            HttpEntity<String>(getDynamicHeaders(testUserId)), 
            WalletCredential::class.java
        )
        val credentialId = issueResponse.body?.id!!

        // Step 3: Selective Presentation. Filter disclosures
        logger.info("Step 3: Selective Presentation. Handshake and presentation filtering")
        val presRequest = PresentationRequest(claimsToDisclose = listOf("nationalities"))
        val presResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/$credentialId/presentation",
            HttpEntity(presRequest, getDynamicHeaders(testUserId)),
            Map::class.java
        )
        val presBody = presResponse.body as Map<String, Any>
        val sdJwtPresentation = presBody["presentation"] as String

        // Step 4: Verification. Relying party check
        logger.info("Step 4: Verification. Validating minimized token at MockRp")
        val verifyRequest = VerificationRequest(sdJwt = sdJwtPresentation, userId = testUserId)
        val verifyResponse = restTemplate.postForEntity("/api/v1/mock-rp/verify", verifyRequest, Map::class.java)

        // Step 5: Final Assertions
        assertThat(verifyResponse.statusCode).isEqualTo(HttpStatus.OK)
        val responseBody = verifyResponse.body as Map<String, Any>
        val verifiedClaims = responseBody["verifiedClaims"] as Map<String, Any>
        
        assertThat(verifiedClaims).containsKey("nationalities")
        assertThat(verifiedClaims).doesNotContainKey("given_name")
        
        logger.info("FinalResult: Integrity verified under dynamic SoleControl policy.")
    }
}
