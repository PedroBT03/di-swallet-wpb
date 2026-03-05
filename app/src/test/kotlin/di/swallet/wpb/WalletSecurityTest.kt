package di.swallet.wpb

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class WalletSecurityTest : BaseIntegrationTest() {

    /**
     * Ensures that the Security Interceptor correctly enforces the Sole Control policy
     * by blocking any request that does not provide a valid authorization header.
     */
    @Test
    fun `security interceptor should block requests without valid authorization`() {
        logger.info("STEP: Testing Security Interceptor - No Header")
        
        val response = restTemplate.postForEntity("/api/v1/wallet/keys/any-user", null, String::class.java)
        
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        logger.info("RESULT: Security policy enforced (401 Unauthorized)")
    }
}