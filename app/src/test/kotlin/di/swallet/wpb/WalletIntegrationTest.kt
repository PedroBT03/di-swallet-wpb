package di.swallet.wpb

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate
    
    // Test logger for descriptive output
    private val logger = LoggerFactory.getLogger(javaClass)

    private val testUserId = "test-user-${UUID.randomUUID()}"
    private val authHeader = "fido2-assertion-mock"

    @Test
    fun `when calling API without auth header then return 401`() {
        logger.info("STEP: Verifying Security Interceptor (Sole Control Policy)")
        
        val response = restTemplate.postForEntity("/api/v1/wallet/keys/$testUserId", null, String::class.java)
        
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        logger.info("RESULT: Request blocked as expected (401 Unauthorized)")
    }

    @Test
    fun `full wallet lifecycle test`() {
        logger.info("STEP 1: Starting Full Lifecycle Test for user: $testUserId")
        
        val headers = HttpHeaders()
        headers.set("X-Wallet-Authorization", authHeader)
        val entity = HttpEntity<String>(headers)

        // 1. Create Key
        logger.info("STEP 2: Requesting EC Key Generation inside Remote WSCD (HSM)")
        val createResponse = restTemplate.postForEntity(
            "/api/v1/wallet/keys/$testUserId", 
            entity, 
            WalletKey::class.java
        )
        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.OK)
        val publicKey = createResponse.body?.publicKeyBase64
        logger.info("RESULT: Key generated. Public Key received (Base64).")

        // 2. Retrieve Key (GET)
        logger.info("STEP 3: Verifying Key Persistence in Database")
        val getResponse = restTemplate.exchange(
            "/api/v1/wallet/keys/$testUserId",
            HttpMethod.GET,
            entity,
            WalletKey::class.java
        )
        assertThat(getResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(getResponse.body?.publicKeyBase64).isEqualTo(publicKey)
        logger.info("RESULT: Metadata found. Status is ${getResponse.body?.status}")

        // 3. Sign Data (POST)
        logger.info("STEP 4: Requesting Digital Signature for data: 'Thesis Signature Test'")
        val signRequest = mapOf("data" to "Thesis Signature Test")
        val signEntity = HttpEntity(signRequest, headers)
        
        val signResponse = restTemplate.postForEntity(
            "/api/v1/wallet/sign/$testUserId",
            signEntity,
            Map::class.java
        )

        assertThat(signResponse.statusCode).isEqualTo(HttpStatus.OK)
        logger.info("RESULT: Signature successful. Algorithm: ${signResponse.body?.get("algorithm")}")
        logger.info("SIGNATURE: ${signResponse.body?.get("signature")}")
    }
}