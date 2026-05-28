package di.swallet.wpb.openid4vci.adapter

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.DeferredIssuanceHandle
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.KeyAttestationTransport
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

class SdkOpenId4VciGatewayIntegrationTest {

    private lateinit var server: WireMockServer

    @BeforeEach
    fun setUp() {
        server = WireMockServer(0)
        server.start()
        configureFor("localhost", server.port())
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `demo-mode false gateway resolves authorizes requests and notifies`() {
        val issuer = "http://localhost:${server.port()}"
        stubStandardMetadata(issuer)
        stubFor(post(urlEqualTo("/oauth2/token"))
            .willReturn(okJson("""{"access_token":"at-123","refresh_token":"rt-123","c_nonce":"nonce-1","cnf":{"jkt":"jkt-1"}}""")))
        stubFor(post(urlEqualTo("/credential"))
            .withRequestBody(containing("\"key_attestation\":\"ka.jwt\""))
            .willReturn(okJson("""{"credential":"signed-credential","notification_id":"notif-1"}""")))
        stubFor(post(urlEqualTo("/credential/notification"))
            .willReturn(aResponse().withStatus(204)))

        val gateway = gateway(issuer)
        val offer = """openid-credential-offer://credential_offer={"credential_issuer":"$issuer","credential_configuration_ids":["pid_jwt"]}"""
        val (resolvedOffer, metadata) = gateway.resolveOffer(offer)
        val proof = proof()

        val prepared = gateway.prepareAuthorization(
            adapterSessionId = "session-1",
            offer = resolvedOffer,
            metadata = metadata,
            proof = proof,
            walletAttestation = wia(),
        )
        val authorized = gateway.authorizeWithCode("session-1", "code-123", prepared.state, wia())
        assertTrue(authorized.accessTokenPresent)

        val outcome = gateway.requestCredential(
            adapterSessionId = "session-1",
            request = IssuanceRequest(credentialConfigurationId = "pid_jwt"),
            proof = proof,
            keyAttestation = ka(),
        )
        assertTrue(outcome is IssuanceOutcome.Issued)
        val issued = (outcome as IssuanceOutcome.Issued).credentials.first()
        assertEquals(IssuanceCredentialFormat.SD_JWT_VC, issued.format)
        assertEquals("signed-credential", issued.rawPayload)

        val notified = gateway.notify("session-1", "notif-1", NotificationEvent.CREDENTIAL_ACCEPTED, "ok")
        assertTrue(notified)
    }

    @Test
    fun `gateway handles deferred issuance path`() {
        val issuer = "http://localhost:${server.port()}"
        stubStandardMetadata(issuer)
        stubFor(post(urlEqualTo("/oauth2/token"))
            .willReturn(okJson("""{"access_token":"at-123","c_nonce":"nonce-1"}""")))
        stubFor(post(urlEqualTo("/credential"))
            .willReturn(okJson("""{"transaction_id":"tx-1","notification_id":"notif-2"}""")))
        stubFor(post(urlEqualTo("/credential/deferred"))
            .willReturn(okJson("""{"credential":"deferred-credential","notification_id":"notif-2"}""")))

        val gateway = gateway(issuer)
        val offer = """openid-credential-offer://credential_offer={"credential_issuer":"$issuer","credential_configuration_ids":["pid_jwt"]}"""
        val (resolvedOffer, metadata) = gateway.resolveOffer(offer)
        val proof = proof()
        val prepared = gateway.prepareAuthorization("session-2", resolvedOffer, metadata, proof, wia())
        gateway.authorizeWithCode("session-2", "code-456", prepared.state, wia())

        val deferred = gateway.requestCredential("session-2", IssuanceRequest(credentialConfigurationId = "pid_jwt"), proof, null)
        assertTrue(deferred is IssuanceOutcome.Deferred)
        val handle = (deferred as IssuanceOutcome.Deferred).handle

        val polled = gateway.queryDeferred(
            adapterSessionId = "session-2",
            handle = DeferredIssuanceHandle(
                transactionId = handle.transactionId,
                notificationId = handle.notificationId,
                serializedContext = handle.serializedContext,
            ),
        )
        assertTrue(polled is di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome.Issued)
    }

    @Test
    fun `strict resolution fails hard when SDK cannot resolve http offer`() {
        val issuer = "http://localhost:${server.port()}"
        stubStandardMetadata(issuer)
        val gateway = gateway(issuer, strictResolution = true)
        val offer = """openid-credential-offer://credential_offer={"credential_issuer":"$issuer","credential_configuration_ids":["pid_jwt"]}"""

        assertThrows(IllegalStateException::class.java) {
            gateway.resolveOffer(offer)
        }
    }

    private fun gateway(issuer: String, strictResolution: Boolean = false): SdkOpenId4VciGateway {
        val properties = OpenId4VciProperties().apply {
            demoMode = false
            sdk.credentialIssuerId = issuer
            sdk.strictResolution = strictResolution
        }
        val signer = object : ProofJwtSigner {
            override fun sign(proof: ProofMaterial, audience: String, cNonce: String?): String {
                return "eyJhbGciOiJFUzI1NiJ9.eyJhdWQiOiIkaudienceIiwiY25vbmNlIjoi${cNonce ?: ""}In0.signature"
            }
        }
        return SdkOpenId4VciGateway(properties, signer)
    }

    private fun stubStandardMetadata(issuer: String) {
        stubFor(get(urlEqualTo("/.well-known/openid-credential-issuer"))
            .willReturn(
                okJson(
                    """
                    {
                      "credential_issuer":"$issuer",
                      "credential_endpoint":"$issuer/credential",
                      "deferred_credential_endpoint":"$issuer/credential/deferred",
                      "notification_endpoint":"$issuer/credential/notification",
                      "authorization_servers":["$issuer"],
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
            ))
        stubFor(get(urlEqualTo("/.well-known/oauth-authorization-server"))
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
            ))
    }

    private fun wia() = di.swallet.wpb.openid4vci.protocol.WalletAttestationTransport(
        jwt = "wia.jwt",
        popJwt = "wia.pop",
        cnfJkt = "jkt-test",
        expiresAt = Instant.now().plusSeconds(3600),
    )

    private fun ka() = KeyAttestationTransport(
        jwt = "ka.jwt",
        keyId = "proof-key-1",
        attestedJkt = "proof-jkt",
        keyStorage = "iso_18045_high",
        certification = "test-cert",
        expiresAt = Instant.now().plusSeconds(3600),
        statusListUri = "/api/v1/wallet/status-lists/PRIMARY_LIST",
        statusListIndex = 10,
    )

    private fun proof(): ProofMaterial {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return ProofMaterial(
            keyId = "key-holder-${UUID.randomUUID()}-1",
            publicKey = kp.public as ECPublicKey,
            algorithm = "ES256",
        )
    }
}
