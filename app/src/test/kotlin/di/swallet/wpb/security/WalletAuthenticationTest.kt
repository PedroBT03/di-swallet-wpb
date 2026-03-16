package di.swallet.wpb.security

import di.swallet.wpb.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder
import java.util.*

class WalletAuthenticationTest : BaseIntegrationTest() {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `should complete a real cryptographic challenge-response handshake`() {
        val userId = "crypto-user-${UUID.randomUUID()}"
        val localCredentialId = "device-${UUID.randomUUID()}"
        
        logger.info("--- STARTING CRYPTOGRAPHIC AUTHENTICATION TEST ---")

        // Step 1: DeviceRegistration
        val devicePubKey = Fido2TestHelper.getPublicKeyBase64(deviceKeyPair)
        val registrationUrl = UriComponentsBuilder
            .fromPath("/api/v1/wallet/auth/register/{userId}")
            .queryParam("credentialId", localCredentialId)
            .queryParam("publicKeyBase64", devicePubKey)
            .buildAndExpand(userId).toUriString()
            
        restTemplate.postForEntity(registrationUrl, null, String::class.java)

        // Step 2: ChallengeRequest
        val authResponse = restTemplate.getForObject("/api/v1/wallet/auth/challenge/$userId", Map::class.java) as Map<String, String>
        val challenge = authResponse["challenge"]!!

        // Step 3: ClientSideSigning - Generating real FIDO2 blobs
        logger.info("Step 3: Signing - Generating standard WebAuthn assertion blobs")
        val assertionMap = Fido2TestHelper.createWebAuthnAssertion(userId, localCredentialId, challenge, deviceKeyPair)

        // Step 4: ServerSideVerification - Using the StandardMode logic
        logger.info("Step 4: Verification - Sending standard FIDO2 JSON header")
        val jsonAssertion = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(assertionMap)
        val encodedAssertion = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonAssertion.toByteArray())
        
        val headers = createAuthHeaders()
        headers.set("X-Wallet-Authorization", "fido2-assertion:$encodedAssertion")
        val entity = HttpEntity<String>(headers)

        val finalResponse = restTemplate.postForEntity("/api/v1/wallet/keys/$userId", entity, String::class.java)

        // Step 5: FinalAssertions
        assertEquals(HttpStatus.OK, finalResponse.statusCode)
        logger.info("FinalResult: Server verified standard WebAuthn assertion. Access granted.")
        logger.info("--- StartingCryptographicAuthenticationTest Completed ---")
    }
}