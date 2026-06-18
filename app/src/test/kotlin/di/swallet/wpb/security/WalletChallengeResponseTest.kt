/**
 * Tests wallet challenge response shape for WPI WebAuthn clients.
 */

package di.swallet.wpb.security

import di.swallet.wpb.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

class WalletChallengeResponseTest : BaseIntegrationTest() {

    /**
     * Returns publicKeyCredentialRequestOptions alongside the challenge so browsers
     * can run the same ceremony WPB stored for finishAssertion.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `challenge endpoint returns server WebAuthn request options`() {
        val userId = "challenge-shape-${UUID.randomUUID()}"
        val credentialId = "device-${UUID.randomUUID()}"
        val pubKey = Fido2TestHelper.getPublicKeyBase64(deviceKeyPair)

        val registrationUrl = UriComponentsBuilder
            .fromPath("/api/v1/wallet/auth/register/{userId}")
            .queryParam("credentialId", credentialId)
            .queryParam("publicKeyBase64", pubKey)
            .buildAndExpand(userId).toUriString()
        restTemplate.postForEntity(registrationUrl, null, String::class.java)

        val response = restTemplate.getForObject(
            "/api/v1/wallet/auth/challenge/$userId",
            Map::class.java,
        ) as Map<String, Any>

        assertEquals(userId, response["userId"])
        assertNotNull(response["challenge"])

        val options = response["publicKeyCredentialRequestOptions"] as Map<String, Any>
        assertEquals(response["challenge"], options["challenge"])
        assertEquals("localhost", options["rpId"])
        assertTrue(options.containsKey("allowCredentials"))
        assertTrue((options["allowCredentials"] as List<*>).isNotEmpty())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `challenge endpoint can narrow to a single credential id`() {
        val userId = "challenge-narrow-${UUID.randomUUID()}"
        val credentialId = "device-${UUID.randomUUID()}"
        val otherCredentialId = "device-${UUID.randomUUID()}"
        val pubKey = Fido2TestHelper.getPublicKeyBase64(deviceKeyPair)

        fun register(credId: String) {
            val registrationUrl = UriComponentsBuilder
                .fromPath("/api/v1/wallet/auth/register/{userId}")
                .queryParam("credentialId", credId)
                .queryParam("publicKeyBase64", pubKey)
                .buildAndExpand(userId).toUriString()
            restTemplate.postForEntity(registrationUrl, null, String::class.java)
        }
        register(credentialId)
        register(otherCredentialId)

        val response = restTemplate.getForObject(
            "/api/v1/wallet/auth/challenge/$userId?credentialId=$credentialId",
            Map::class.java,
        ) as Map<String, Any>

        val options = response["publicKeyCredentialRequestOptions"] as Map<String, Any>
        val allowCredentials = options["allowCredentials"] as List<Map<String, Any>>
        assertEquals(1, allowCredentials.size)
        assertEquals(credentialId, allowCredentials.first()["id"])
        assertEquals("preferred", options["userVerification"])
    }
}
