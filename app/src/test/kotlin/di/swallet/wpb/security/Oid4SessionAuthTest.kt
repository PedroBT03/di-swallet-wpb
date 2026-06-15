package di.swallet.wpb.security

import di.swallet.wpb.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

class Oid4SessionAuthTest : BaseIntegrationTest() {

    @Test
    fun `rejects OID4 session read without FIDO2`() {
        val response = restTemplate.getForEntity(
            "/openid4vp/session/${UUID.randomUUID()}",
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
