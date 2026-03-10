package di.swallet.wpb

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class BaseIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    protected val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Performs a security handshake to get a dynamic FIDO2 authorization header.
     * Every call to this function fetches a new challenge from the server.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun getDynamicHeaders(userId: String): HttpHeaders {
        // 1. Request a new challenge from the auth endpoint
        val authResponse = restTemplate.getForObject(
            "/api/v1/wallet/auth/challenge/$userId", 
            Map::class.java
        ) as Map<String, String>
        
        val challenge = authResponse["challenge"] ?: throw RuntimeException("Failed to get challenge")
        
        // 2. Construct the dynamic header: fido2-userId:challenge
        val headers = HttpHeaders()
        headers.set("X-Wallet-Authorization", "fido2-$userId:$challenge")
        return headers
    }
}