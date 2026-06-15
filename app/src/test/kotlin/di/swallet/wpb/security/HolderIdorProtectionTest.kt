package di.swallet.wpb.security

import di.swallet.wpb.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.UUID

class HolderIdorProtectionTest : BaseIntegrationTest() {

    @Test
    fun `rejects protected request when path userId does not match authenticated holder`() {
        val authenticatedHolder = "holder-a-${UUID.randomUUID()}"
        val otherHolder = "holder-b-${UUID.randomUUID()}"

        val headers = getDynamicHeaders(authenticatedHolder)
        val entity = HttpEntity<String>(headers)

        val response = restTemplate.postForEntity(
            "/api/v1/wallet/keys/$otherHolder",
            entity,
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `rejects protected request when query holderId does not match authenticated holder`() {
        val authenticatedHolder = "holder-a-${UUID.randomUUID()}"
        val otherHolder = "holder-b-${UUID.randomUUID()}"

        val headers = getDynamicHeaders(authenticatedHolder)
        val entity = HttpEntity<String>(headers)

        val response = restTemplate.exchange(
            "/api/v1/wallet/transactions?holderId=$otherHolder",
            org.springframework.http.HttpMethod.GET,
            entity,
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }
}
