package di.swallet.wpb.security

import di.swallet.wpb.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class WalletSecurityTest : BaseIntegrationTest() {

    /**
     * Verifies that the interceptor blocks requests without the custom header.
     */
    @Test
    fun `security interceptor should block requests without valid authorization`() {
        logger.info("Testing access without header")
        val response = restTemplate.postForEntity("/api/v1/wallet/keys/any-user", null, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        logger.info("Result: Request blocked successfully")
    }

    /**
     * Verifies that the interceptor blocks requests with an incorrect or expired challenge.
     */
    @Test
    fun `security interceptor should block invalid challenge format`() {
        logger.info("Testing with malformed challenge")
        val headers = org.springframework.http.HttpHeaders()
        headers.set("X-Wallet-Authorization", "fido2-user:wrong-challenge")
        val entity = org.springframework.http.HttpEntity<String>(headers)
        
        val response = restTemplate.postForEntity("/api/v1/wallet/keys/user", entity, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        logger.info("Result: Malformed challenge rejected")
    }
}