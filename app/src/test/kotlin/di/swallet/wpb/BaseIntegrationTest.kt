package di.swallet.wpb

import di.swallet.wpb.security.Fido2TestHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class BaseIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    protected val logger = LoggerFactory.getLogger(javaClass)

    // Generate a fresh keypair for each test class
    protected val deviceKeyPair = Fido2TestHelper.generateDeviceKeyPair()

    protected fun createAuthHeaders(): HttpHeaders = HttpHeaders()

    /**
     * Performs a dynamic security handshake.
     * Generates a unique credentialId per user to avoid database collisions.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun getDynamicHeaders(userId: String): HttpHeaders {
        val credentialId = "device-${UUID.randomUUID()}"
        val pubKey = Fido2TestHelper.getPublicKeyBase64(deviceKeyPair)
        
        // 1. Register Device
        val registrationUrl = org.springframework.web.util.UriComponentsBuilder
            .fromPath("/api/v1/wallet/auth/register/{userId}")
            .queryParam("credentialId", credentialId)
            .queryParam("publicKeyBase64", pubKey)
            .buildAndExpand(userId).toUriString()
        restTemplate.postForEntity(registrationUrl, null, String::class.java)

        // 2. Request Challenge
        val authResponse = restTemplate.getForObject("/api/v1/wallet/auth/challenge/$userId", Map::class.java) as Map<String, String>
        val challenge = authResponse["challenge"]!!

        // 3. Generate REAL WebAuthn Assertion via Helper
        val assertionMap = Fido2TestHelper.createWebAuthnAssertion(userId, credentialId, challenge, deviceKeyPair)
        
        // 4. Encode as JSON and create the production header
        val jsonAssertion = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(assertionMap)
        val encodedAssertion = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonAssertion.toByteArray())

        val headers = HttpHeaders()
        headers.set("X-Wallet-Authorization", "fido2-assertion:$encodedAssertion")
        return headers
    }
}