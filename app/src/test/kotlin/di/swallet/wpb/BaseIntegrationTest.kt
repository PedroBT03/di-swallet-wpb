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
    protected val authHeader = "fido2-assertion-mock"

    /**
     * Generates the mandatory security headers for the Wallet API.
     */
    protected fun createAuthHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        headers.set("X-Wallet-Authorization", authHeader)
        return headers
    }
}