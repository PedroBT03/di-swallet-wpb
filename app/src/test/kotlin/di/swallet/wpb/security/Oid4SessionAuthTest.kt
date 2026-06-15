/**
 * Tests oid4 session auth.
 */

package di.swallet.wpb.security

import di.swallet.wpb.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

class Oid4SessionAuthTest : BaseIntegrationTest() {

    /**
     * GETs an OpenID4VP session by id without FIDO2 authentication headers and expects
     * HTTP 401 because OID4 session reads require holder authentication.
     */
    @Test
    fun `rejects OID4 session read without FIDO2`() {
        val response = restTemplate.getForEntity(
            "/openid4vp/session/${UUID.randomUUID()}",
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
