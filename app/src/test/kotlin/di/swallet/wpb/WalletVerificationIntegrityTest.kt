package di.swallet.wpb

import di.swallet.wpb.controller.PresentationRequest
import di.swallet.wpb.controller.VerificationRequest
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.*

/**
 * End-to-End Integrity tests for the Selective Disclosure flow.
 * Verifies that the Wallet can generate a minimized presentation and 
 * the Mock RP can successfully verify it.
 */
class WalletVerificationIntegrityTest : BaseIntegrationTest() {

    /**
     * Validates the complete EUDI Trust Triangle:
     * 1. Key Generation (Holder)
     * 2. SD-JWT Issuance (Issuer)
     * 3. Selective Presentation (Holder)
     * 4. Cryptographic Verification (Verifier)
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `should perform full selective disclosure and verification flow`() {
        val testUserId = "integrity-test-user-${UUID.randomUUID()}"
        val headers = createAuthHeaders()
        
        // --- Step 1: Initialization ---
        logger.info("STEP 1: Initializing hardware key for $testUserId")
        restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", HttpEntity<String>(headers), WalletKey::class.java)

        // --- Step 2: Issuance ---
        logger.info("STEP 2: Issuing full SD-JWT from Mock Issuer")
        val issueResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/issue-sd/$testUserId", 
            HttpEntity<String>(headers), 
            WalletCredential::class.java
        )
        val credentialId = issueResponse.body?.id ?: throw RuntimeException("Failed to issue credential")

        // --- Step 3: Selective Presentation ---
        logger.info("STEP 3: Creating minimized presentation (disclosing only 'nationality')")
        val presRequest = PresentationRequest(claimsToDisclose = listOf("nationality"))
        val presResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/$credentialId/presentation",
            HttpEntity(presRequest, headers),
            Map::class.java
        )
        
        val presBody = presResponse.body as Map<String, Any>
        val sdJwtPresentation = presBody["presentation"] as String

        // --- Step 4: Verification ---
        logger.info("STEP 4: Sending minimized token to Mock RP for verification")
        val verifyRequest = VerificationRequest(sdJwt = sdJwtPresentation, userId = testUserId)
        val verifyResponse = restTemplate.postForEntity(
            "/api/v1/mock-rp/verify",
            verifyRequest,
            Map::class.java
        )

        // --- Assertions ---
        assertThat(verifyResponse.statusCode).isEqualTo(HttpStatus.OK)
        
        val responseBody = verifyResponse.body as Map<String, Any>
        assertThat(responseBody["status"]).isEqualTo("VALID")
        
        val verifiedClaims = responseBody["verifiedClaims"] as Map<String, Any>
        
        // Verification: Verifier sees the authorized claim
        assertThat(verifiedClaims).containsKey("nationality")
        assertThat(verifiedClaims["nationality"]).isEqualTo("PT")
        
        // Privacy Proof: Verifier does not see the undisclosed claims
        assertThat(verifiedClaims).doesNotContainKey("given_name")
        assertThat(verifiedClaims).doesNotContainKey("family_name")
        
        logger.info("RESULT: Integrity verified. Signature is valid and hidden claims remain private.")
    }
}