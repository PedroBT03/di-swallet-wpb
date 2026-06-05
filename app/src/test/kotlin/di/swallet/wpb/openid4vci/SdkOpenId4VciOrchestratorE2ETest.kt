package di.swallet.wpb.openid4vci

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.verify
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.orchestration.IssuanceFlowOrchestrator
import di.swallet.wpb.issuance.trust.SignedIssuerMetadataTestSupport
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.service.HsmService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * Exercises the full issuance orchestrator against [SdkOpenId4VciGateway]
 * (demo-mode=false) with a local WireMock issuer and HSM-backed proof material.
 */
class SdkOpenId4VciOrchestratorE2ETest : BaseIntegrationTest() {

    @Autowired
    lateinit var orchestrator: IssuanceFlowOrchestrator

    @Autowired
    lateinit var hsmService: HsmService

    companion object {
        private val wireMock: WireMockServer = WireMockServer(0).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configure(registry: DynamicPropertyRegistry) {
            val issuer = issuerBaseUrl()
            registry.add("wpb.openid4vci.demo-mode") { "false" }
            registry.add("wpb.openid4vci.trust.allowed-issuer-ids") { issuer }
            registry.add("wpb.openid4vci.sdk.strict-resolution") { "false" }
            registry.add("wpb.openid4vci.sdk.credential-issuer-id") { issuer }
            registry.add("wpb.openid4vci.sdk.metadata-policy") { "preferSigned" }
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMock.stop()
        }

        private fun issuerBaseUrl(): String = "http://localhost:${wireMock.port()}"
    }

    @BeforeEach
    fun resetWireMock() {
        wireMock.resetAll()
        configureFor("localhost", wireMock.port())
        stubIssuerMetadata(signedMetadataJwt = null)
        stubTokenEndpoint("placeholder-jkt")
        stubFor(
            post(urlEqualTo("/credential"))
                .willReturn(okJson("""{"credential":"sdk-signed-credential","notification_id":"notif-sdk-1"}""")),
        )
        stubFor(post(urlEqualTo("/credential/notification")).willReturn(aResponse().withStatus(204)))
    }

    @Test
    fun `sdk gateway orchestrator flow uses hsm proof keys against wiremock issuer`() {
        val holderId = "sdk-orchestrator-${UUID.randomUUID()}"
        hsmService.generateKeyForUser(holderId)
        val issuer = issuerBaseUrl()
        val offer =
            """openid-credential-offer://credential_offer={"credential_issuer":"$issuer","credential_configuration_ids":["pid_jwt"]}"""

        var ctx = orchestrator.resolveOffer(offer, holderId)
        assertEquals(IssuanceState.OFFER_RESOLVED, ctx.state)
        assertTrue(ctx.trustDecision!!.trusted)
        assertEquals(issuer, ctx.credentialIssuerId)

        ctx = orchestrator.prepareAuthorization(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.AUTHORIZATION_PREPARED, ctx.state)
        val wiaCnfJkt = ctx.wia!!.attestation!!.cnfJkt
        assertNotNull(wiaCnfJkt)
        stubTokenEndpoint(wiaCnfJkt)

        ctx = orchestrator.completeAuthorizationCode(
            ctx.sessionMeta.sessionId,
            authorizationCode = "auth-code-sdk",
            state = ctx.preparedAuthorization!!.state,
        )
        assertEquals(IssuanceState.AUTHORIZED, ctx.state)

        ctx = orchestrator.requestCredential(
            ctx.sessionMeta.sessionId,
            IssuanceRequest(credentialConfigurationId = "pid_jwt"),
        )
        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
        assertEquals(1, ctx.issuedCredentials.size)

        val walletKey = hsmService.getUserKey(holderId)
        verify(
            postRequestedFor(urlEqualTo("/credential"))
                .withRequestBody(matchingJsonPath("$.proof.jwk.kid", containing(walletKey.keyAlias)))
                .withRequestBody(matchingJsonPath("$.proof.jwt")),
        )

        val proofJwt = extractProofJwtFromLastCredentialRequest()
        val parsed = SignedJWT.parse(proofJwt)
        assertEquals(walletKey.keyAlias, parsed.header.keyID)
        val publicKey = hsmService.getUserKey(holderId)
        val ecPublicKey = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getUrlDecoder().decode(publicKey.publicKeyBase64)))
            as java.security.interfaces.ECPublicKey
        assertTrue(parsed.verify(ECDSAVerifier(ecPublicKey)))
    }

    @Test
    fun `valid signed metadata allows sdk orchestrator offer resolution`() {
        val issuer = issuerBaseUrl()
        val signing = SignedIssuerMetadataTestSupport.generateIssuerSigningMaterial()
        val signedJwt = SignedIssuerMetadataTestSupport.signedMetadataJwt(issuer, signing)
        stubIssuerMetadata(signedMetadataJwt = signedJwt)

        val holderId = "sdk-signed-meta-${UUID.randomUUID()}"
        val offer =
            """openid-credential-offer://credential_offer={"credential_issuer":"$issuer","credential_configuration_ids":["pid_jwt"]}"""

        val ctx = orchestrator.resolveOffer(offer, holderId)
        assertEquals(IssuanceState.OFFER_RESOLVED, ctx.state)
        assertTrue(ctx.trustDecision!!.trusted)
        assertTrue(ctx.issuerMetadata!!.signedMetadataPresent)
        assertEquals(signedJwt, ctx.issuerMetadata!!.signedMetadataJwt)
    }

    private fun stubIssuerMetadata(signedMetadataJwt: String?) {
        val issuer = issuerBaseUrl()
        val signedField = signedMetadataJwt?.let { ""","signed_metadata":"$it"""" } ?: ""
        stubFor(
            get(urlEqualTo("/.well-known/openid-credential-issuer"))
                .willReturn(
                    okJson(
                        """
                        {
                          "credential_issuer":"$issuer",
                          "credential_endpoint":"$issuer/credential",
                          "notification_endpoint":"$issuer/credential/notification",
                          "authorization_servers":["$issuer"]$signedField,
                          "credential_configurations_supported":{
                            "pid_jwt":{
                              "format":"sd_jwt_vc",
                              "vct":"pid_jwt",
                              "cryptographic_binding_methods_supported":["jwk"],
                              "proof_types_supported":{"jwt":{},"attestation":{}},
                              "display":[{"name":"PID","locale":"en"}]
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
        )
        stubFor(
            get(urlEqualTo("/.well-known/oauth-authorization-server"))
                .willReturn(
                    okJson(
                        """
                        {
                          "issuer":"$issuer",
                          "authorization_endpoint":"$issuer/oauth2/authorize",
                          "token_endpoint":"$issuer/oauth2/token",
                          "grant_types_supported":["authorization_code","urn:ietf:params:oauth:grant-type:pre-authorized_code"],
                          "dpop_signing_alg_values_supported":["ES256"]
                        }
                        """.trimIndent(),
                    ),
                ),
        )
    }

    private fun stubTokenEndpoint(cnfJkt: String) {
        stubFor(
            post(urlEqualTo("/oauth2/token"))
                .willReturn(
                    okJson(
                        """
                        {
                          "access_token":"at-sdk-test",
                          "c_nonce":"nonce-sdk-test",
                          "cnf":{"jkt":"$cnfJkt"}
                        }
                        """.trimIndent(),
                    ),
                ),
        )
    }

    private fun extractProofJwtFromLastCredentialRequest(): String {
        val requests = wireMock.findAll(postRequestedFor(urlEqualTo("/credential")))
        assertTrue(requests.isNotEmpty())
        val body = requests.last().bodyAsString
        val marker = "\"jwt\":\""
        val start = body.indexOf(marker)
        require(start >= 0) { "proof jwt not found in credential request" }
        val from = start + marker.length
        val end = body.indexOf('"', from)
        require(end > from) { "proof jwt terminator not found" }
        return body.substring(from, end)
    }
}
