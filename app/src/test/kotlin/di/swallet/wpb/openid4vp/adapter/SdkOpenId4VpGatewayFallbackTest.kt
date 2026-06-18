/**
 * Tests demo-mode fallback parsing for local verifier emulator authorization requests.
 */

package di.swallet.wpb.openid4vp.adapter

import com.sun.net.httpserver.HttpServer
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import eu.europa.ec.eudi.openid4vp.OpenId4Vp
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64

class SdkOpenId4VpGatewayFallbackTest {

    @Test
    fun `demo mode prefers local emulator fallback over sdk missing client id`() = runBlocking {
        val jwt = sampleAuthorizationRequestJwt()
        val server = startRequestServer(jwt)
        try {
            val gateway = SdkOpenId4VpGateway(mock(OpenId4Vp::class.java), demoMode = true)
            val requestUri = "http://127.0.0.1:${server.address.port}/request/conformance/simple_claim.json"
            val resolution = gateway.resolveRequestUri(requestUri)
            assertTrue(resolution is AuthorizationRequestResolution.Success)
            val request = (resolution as AuthorizationRequestResolution.Success).request
            assertEquals("verifier-demo-client", request.clientId)
            assertTrue(request.verifierInfoJson?.contains("x5c") == true)
        } finally {
            server.stop(0)
        }
    }

    private fun sampleAuthorizationRequestJwt(): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"alg":"ES256","typ":"JWT"}""".toByteArray(StandardCharsets.UTF_8),
        )
        val payloadJson = """
            {
              "client_id": "verifier-demo-client",
              "response_type": "vp_token",
              "response_mode": "direct_post",
              "response_uri": "http://127.0.0.1:8081/direct_post",
              "state": "conformance-simple-claim",
              "nonce": "nonce-conformance-simple-claim",
              "dcql": {
                "credentials": [
                  {
                    "id": "pid",
                    "format": "vc+sd-jwt",
                    "meta": { "vct_values": ["PID"] },
                    "claims": [{ "path": ["given_name"] }]
                  }
                ]
              },
              "verifier_info": { "x5c": ["QUJD"] }
            }
        """.trimIndent()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            payloadJson.toByteArray(StandardCharsets.UTF_8),
        )
        return "$header.$payload.signature"
    }

    private fun startRequestServer(body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/request") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                return@createContext
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/oauth-authz-req+jwt")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }
}
