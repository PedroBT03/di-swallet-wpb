package di.swallet.wpb

import di.swallet.wpb.security.Fido2TestHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpEntity
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class BaseIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    protected val logger = LoggerFactory.getLogger(javaClass)

    // Real cryptographic keys for simulation across all tests
    protected val deviceKeyPair = Fido2TestHelper.generateDeviceKeyPair()
    protected val testCredentialId = "device-${UUID.randomUUID()}"

    /**
     * Returns a basic HttpHeaders object.
     */
    protected fun createAuthHeaders(): HttpHeaders {
        return HttpHeaders()
    }

    /**
     * Performs a dynamic security handshake (Register -> Challenge -> Sign) 
     * to return a valid authorization header.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun getDynamicHeaders(userId: String): HttpHeaders {
        // 1. Register device
        val pubKey = Fido2TestHelper.getPublicKeyBase64(deviceKeyPair)
        val registrationUrl = org.springframework.web.util.UriComponentsBuilder
            .fromPath("/api/v1/wallet/auth/register/{userId}")
            .queryParam("credentialId", testCredentialId)
            .queryParam("publicKeyBase64", pubKey)
            .buildAndExpand(userId)
            .toUriString()

        restTemplate.postForEntity(registrationUrl, null, String::class.java)

        // 2. Request challenge
        val authResponse = restTemplate.getForObject("/api/v1/wallet/auth/challenge/$userId", Map::class.java) as Map<String, String>
        val challenge = authResponse["challenge"]!!

        // 3. Sign challenge
        val signature = Fido2TestHelper.signChallenge(deviceKeyPair.private, challenge)

        val headers = HttpHeaders()
        headers.set("X-Wallet-Authorization", "fido2-$userId:$testCredentialId:$signature")
        return headers
    }
}