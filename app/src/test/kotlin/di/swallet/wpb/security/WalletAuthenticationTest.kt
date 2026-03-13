package di.swallet.wpb.security

import di.swallet.wpb.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.*

/**
 * Technical validation of the FIDO2/WebAuthn Cryptographic Handshake.
 * This test simulates a physical device performing real ECDSA signing.
 */
class WalletAuthenticationTest : BaseIntegrationTest() {

    @Test
    fun `should complete a real cryptographic challenge-response handshake`() {
        val userId = "crypto-user-${UUID.randomUUID()}"
        
        logger.info("--- STARTING CRYPTOGRAPHIC AUTHENTICATION TEST ---")

        // Step 1: Device Registration
        // User pairing their phone's public key with the WPB.
        val devicePubKey = Fido2TestHelper.getPublicKeyBase64(deviceKeyPair)
        logger.info("Step 1: Registering Device. Public Key (Base64): $devicePubKey")
        
        val registrationUrl = org.springframework.web.util.UriComponentsBuilder
            .fromPath("/api/v1/wallet/auth/register/{userId}")
            .queryParam("credentialId", testCredentialId)
            .queryParam("publicKeyBase64", devicePubKey)
            .buildAndExpand(userId)
            .toUriString()
            
        val regResponse = restTemplate.postForEntity(registrationUrl, null, String::class.java)

        assertThat(regResponse.statusCode).isEqualTo(HttpStatus.OK)

        // Step 2: Challenge Request
        // The server provides a nonce.
        logger.info("Step 2: Requesting Auth Challenge from server")
        val authResponse = restTemplate.getForObject(
            "/api/v1/wallet/auth/challenge/$userId", 
            Map::class.java
        ) as Map<*, *>
        val challenge = authResponse["challenge"] as String
        logger.info("Result: Received Challenge: $challenge")

        // Step 3: Client-Side Signing
        // The Fido2TestHelper simulates the phone's Secure Enclave signing the challenge.
        logger.info("Step 3: Simulating device signature using Private Key (secp256r1)")
        val signature = Fido2TestHelper.signChallenge(deviceKeyPair.private, challenge)
        logger.info("Result: Generated Signature: $signature")

        // Step 4: Server-Side Verification
        logger.info("Step 4: Sending 3-part authorization header to server")
        val headers = createAuthHeaders() // Clear headers
        headers.set("X-Wallet-Authorization", "fido2-$userId:$testCredentialId:$signature")
        val entity = HttpEntity<String>(headers)

        val finalResponse = restTemplate.postForEntity(
            "/api/v1/wallet/keys/$userId", 
            entity, 
            String::class.java
        )

        // Step 5: Final Assertion
        assertThat(finalResponse.statusCode).isEqualTo(HttpStatus.OK)
        logger.info("Final Result: Server verified the ECDSA signature and granted access to the HSM.")
        logger.info("--- CRYPTOGRAPHIC AUTHENTICATION TEST COMPLETED ---")
    }
}