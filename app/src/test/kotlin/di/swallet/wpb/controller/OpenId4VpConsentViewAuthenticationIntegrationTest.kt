/**
 * Verifies FIDO2-protected OpenID4VP consent-view access end-to-end.
 */

package di.swallet.wpb.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.openid4vp.protocol.AuthorizationStartRequest
import di.swallet.wpb.security.Fido2TestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class OpenId4VpConsentViewAuthenticationIntegrationTest : BaseIntegrationTest() {

  private val objectMapper = ObjectMapper().findAndRegisterModules()

  @Test
  @Suppress("UNCHECKED_CAST")
  fun `consent-view returns 200 when FIDO2 matches session holder`() {
    val userId = "vp-consent-${UUID.randomUUID()}"
    val credentialId = "device-${UUID.randomUUID()}"
    registerDevice(userId, credentialId)

    val server = startVerifierServer()
    try {
      val requestUri = "http://127.0.0.1:${server.address.port}/request/conformance/simple_claim.json"
      val authorizeResponse = restTemplate.postForEntity(
        "/openid4vp/authorize",
        AuthorizationStartRequest(requestUri = requestUri, holderId = userId),
        Map::class.java,
      )
      assertEquals(HttpStatus.OK, authorizeResponse.statusCode)

      val sessionMeta = authorizeResponse.body?.get("sessionMeta") as Map<*, *>
      val sessionId = sessionMeta["sessionId"] as String
      val headers = freshAuthHeaders(userId, credentialId)

      val consentResponse = restTemplate.exchange(
        "/openid4vp/session/$sessionId/consent-view?holderId=$userId",
        HttpMethod.GET,
        HttpEntity<Void>(headers),
        Map::class.java,
      )

      assertEquals(HttpStatus.OK, consentResponse.statusCode)
      assertEquals(sessionId, consentResponse.body?.get("sessionId"))
      assertEquals(userId, consentResponse.body?.get("holderId"))
      assertEquals("CONSENT_PENDING", consentResponse.body?.get("state"))
    } finally {
      server.stop(0)
    }
  }

  private fun registerDevice(userId: String, credentialId: String) {
    val registrationUrl = UriComponentsBuilder
      .fromPath("/api/v1/wallet/auth/register/{userId}")
      .queryParam("credentialId", credentialId)
      .queryParam("publicKeyBase64", Fido2TestHelper.getPublicKeyBase64(deviceKeyPair))
      .buildAndExpand(userId)
      .toUriString()
    restTemplate.postForEntity(registrationUrl, null, String::class.java)
  }

  private fun freshAuthHeaders(userId: String, credentialId: String) =
    createAuthHeaders(userId, credentialId, deviceKeyPair)

  @Suppress("UNCHECKED_CAST")
  private fun createAuthHeaders(
    userId: String,
    credentialId: String,
    keyPair: java.security.KeyPair,
  ): org.springframework.http.HttpHeaders {
    val authResponse = restTemplate.getForObject(
      "/api/v1/wallet/auth/challenge/$userId",
      Map::class.java,
    ) as Map<String, String>
    val challenge = authResponse["challenge"]!!
    val assertionMap = Fido2TestHelper.createWebAuthnAssertion(userId, credentialId, challenge, keyPair)
    val jsonAssertion = objectMapper.writeValueAsString(assertionMap)
    val encodedAssertion = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonAssertion.toByteArray())
    return org.springframework.http.HttpHeaders().apply {
      set("X-Wallet-Authorization", "fido2-assertion:$encodedAssertion")
    }
  }

  private fun startVerifierServer(): HttpServer {
    val jwt = sampleAuthorizationRequestJwt()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/request") { exchange ->
      val bytes = jwt.toByteArray(StandardCharsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/oauth-authz-req+jwt")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server
  }

  private fun sampleAuthorizationRequestJwt(): String {
    val x5c = DefaultResourceLoader()
      .getResource("classpath:trust/demo-verifier-access.pem")
      .inputStream
      .bufferedReader()
      .readText()
      .lineSequence()
      .filter { it.isNotBlank() && !it.startsWith("-----") }
      .joinToString("")
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
        "verifier_info": { "x5c": ["$x5c"] }
      }
    """.trimIndent()
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
      payloadJson.toByteArray(StandardCharsets.UTF_8),
    )
    return "$header.$payload.signature"
  }
}
