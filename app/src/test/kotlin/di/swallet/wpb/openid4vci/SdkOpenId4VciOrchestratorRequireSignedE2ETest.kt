package di.swallet.wpb.openid4vci

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.orchestration.IssuanceFlowOrchestrator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

class SdkOpenId4VciOrchestratorRequireSignedE2ETest : BaseIntegrationTest() {

    @Autowired
    lateinit var orchestrator: IssuanceFlowOrchestrator

    companion object {
        private val wireMock: WireMockServer = WireMockServer(0).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configure(registry: DynamicPropertyRegistry) {
            val issuer = "http://localhost:${wireMock.port()}"
            registry.add("wpb.openid4vci.demo-mode") { "false" }
            registry.add("wpb.openid4vci.trust.allowed-issuer-ids") { issuer }
            registry.add("wpb.openid4vci.sdk.strict-resolution") { "false" }
            registry.add("wpb.openid4vci.sdk.metadata-policy") { "requireSigned" }
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMock.stop()
        }
    }

    @BeforeEach
    fun stubUnsignedMetadata() {
        wireMock.resetAll()
        configureFor("localhost", wireMock.port())
        val issuer = "http://localhost:${wireMock.port()}"
        stubFor(
            get(urlEqualTo("/.well-known/openid-credential-issuer"))
                .willReturn(
                    okJson(
                        """
                        {
                          "credential_issuer":"$issuer",
                          "credential_endpoint":"$issuer/credential",
                          "authorization_servers":["$issuer"],
                          "credential_configurations_supported":{
                            "pid_jwt":{"format":"sd_jwt_vc","vct":"pid_jwt"}
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
                          "token_endpoint":"$issuer/oauth2/token",
                          "grant_types_supported":["authorization_code"]
                        }
                        """.trimIndent(),
                    ),
                ),
        )
    }

    @Test
    fun `requireSigned rejects unsigned issuer metadata in sdk orchestrator flow`() {
        val issuer = "http://localhost:${wireMock.port()}"
        val offer =
            """openid-credential-offer://credential_offer={"credential_issuer":"$issuer","credential_configuration_ids":["pid_jwt"]}"""

        val ctx = orchestrator.resolveOffer(offer, "holder-${UUID.randomUUID()}")
        assertEquals(IssuanceState.REJECTED, ctx.state)
        assertEquals("issuer_untrusted", ctx.error?.code)
        assertTrue(ctx.error?.message?.contains("signed_metadata") == true)
    }
}
