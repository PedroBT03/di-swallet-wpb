package di.swallet.wpb.openid4vci.adapter

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.DeferredIssuanceHandle
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.openid4vci.protocol.AuthorizationFlowKind
import di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class SimulatedOpenId4VciGatewayTest {

    private fun gateway(properties: OpenId4VciProperties = OpenId4VciProperties()) = SimulatedOpenId4VciGateway(properties)

    private fun proof(): ProofMaterial {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return ProofMaterial(keyId = "key-1", publicKey = kp.public as ECPublicKey, algorithm = "ES256")
    }

    @Test
    fun `resolves authorization_code offer by value`() {
        val gw = gateway()
        val offer = """
            openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}
        """.trimIndent().replace("\n", "")
        val (resolved, metadata) = gw.resolveOffer(offer)
        assertEquals("https://issuer.example", resolved.credentialIssuerId)
        assertEquals(AuthorizationFlowKind.AUTHORIZATION_CODE, resolved.authorizationFlow)
        assertEquals(listOf("pid_jwt"), resolved.credentialConfigurationIds)
        assertTrue(metadata.authorizationServers.any { it.supportsPar })
    }

    @Test
    fun `resolves pre-authorized_code offer with tx_code`() {
        val gw = gateway()
        val offer = """
            openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"],"grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"tx_code":{"length":4}}}}
        """.trimIndent().replace("\n", "")
        val (resolved, _) = gw.resolveOffer(offer)
        assertEquals(AuthorizationFlowKind.PRE_AUTHORIZED_CODE, resolved.authorizationFlow)
        assertNotNull(resolved.preAuthorizedGrant)
        assertTrue(resolved.preAuthorizedGrant!!.txCodeRequired)
    }

    @Test
    fun `authorization code happy path issues an SD-JWT VC`() {
        val gw = gateway()
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof())
        val authorized = gw.authorizeWithCode(sessionId, "code-123", prepared.state)
        assertTrue(authorized.accessTokenPresent)

        val outcome = gw.requestCredential(sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"), proof())
        assertTrue(outcome is IssuanceOutcome.Issued)
        val issued = (outcome as IssuanceOutcome.Issued).credentials.first()
        assertTrue(issued.rawPayload.contains("~"))
    }

    @Test
    fun `state mismatch on authorization code is rejected`() {
        val gw = gateway()
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        gw.prepareAuthorization(sessionId, offer, metadata, proof())
        assertThrows(IllegalArgumentException::class.java) {
            gw.authorizeWithCode(sessionId, "code-1", "wrong-state")
        }
    }

    @Test
    fun `deferred outcome can be polled to completion`() {
        val props = OpenId4VciProperties().apply {
            simulator.alwaysDefer = true
            simulator.deferredPollsBeforeIssue = 2
        }
        val gw = SimulatedOpenId4VciGateway(props)
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof())
        gw.authorizeWithCode(sessionId, "code-x", prepared.state)
        val req = gw.requestCredential(sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"), proof())
        assertTrue(req is IssuanceOutcome.Deferred)
        var handle = (req as IssuanceOutcome.Deferred).handle

        val first = gw.queryDeferred(sessionId, handle)
        assertTrue(first is DeferredQueryOutcome.StillPending)
        handle = (first as DeferredQueryOutcome.StillPending).updatedHandle

        val second = gw.queryDeferred(sessionId, handle)
        assertTrue(second is DeferredQueryOutcome.Issued)
    }

    @Test
    fun `requestCredential without authorization returns failed outcome`() {
        val gw = gateway()
        val outcome = gw.requestCredential(UUID.randomUUID().toString(), IssuanceRequest("pid_jwt"), proof())
        assertTrue(outcome is IssuanceOutcome.Failed)
    }

    @Test
    fun `mdoc credential configuration is rejected at adapter level`() {
        val gw = gateway()
        // Force mdoc metadata using wallet-initiated resolveMetadata
        val metadata = gw.resolveMetadata("https://issuer.example", listOf("driver_license"))
        // Override the configuration format to mdoc through internal state by providing an offer
        // We re-resolve with an offer pointing at the same id but mdoc descriptor will come back as SD_JWT.
        // To exercise the MDOC guard, we forge a request to mdoc:
        val (offer, _) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["driver_license"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata.copy(
            credentialConfigurations = metadata.credentialConfigurations.map {
                it.copy(format = di.swallet.wpb.issuance.domain.IssuanceCredentialFormat.MSO_MDOC)
            },
        ), proof())
        gw.authorizeWithCode(sessionId, "c", prepared.state)
        val outcome = gw.requestCredential(sessionId, IssuanceRequest(credentialConfigurationId = "driver_license"), proof())
        assertTrue(outcome is IssuanceOutcome.Failed)
        assertEquals("unsupported_format", (outcome as IssuanceOutcome.Failed).code)
    }

    @Test
    fun `notify and discard drop adapter state`() {
        val gw = gateway()
        val sessionId = UUID.randomUUID().toString()
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
        )
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof())
        gw.authorizeWithCode(sessionId, "c", prepared.state)
        gw.requestCredential(sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"), proof())
        assertTrue(gw.notify(sessionId, "notif-1", NotificationEvent.CREDENTIAL_ACCEPTED))
        gw.discard(sessionId)
        val again = gw.queryDeferred(sessionId, DeferredIssuanceHandle("tx-1"))
        assertTrue(again is DeferredQueryOutcome.Failed)
    }

    @Test
    fun `pre-authorized flow without tx_code fails when issuer requires it`() {
        val gw = gateway()
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"],"grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"tx_code":{"length":4}}}}""",
        )
        val sessionId = UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) {
            gw.authorizeWithPreAuthorizedCode(sessionId, offer, metadata, proof(), txCode = null)
        }
    }

    @Test
    fun `wallet-initiated metadata returns the requested ids`() {
        val gw = gateway()
        val metadata = gw.resolveMetadata("https://issuer.example", listOf("a", "b"))
        assertEquals(2, metadata.credentialConfigurations.size)
        assertEquals(listOf("a", "b"), metadata.credentialConfigurations.map { it.id })
    }
}
