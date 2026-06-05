package di.swallet.wpb.openid4vci.adapter

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.DeferredIssuanceHandle
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.openid4vci.protocol.AuthorizationFlowKind
import di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.KeyAttestationTransport
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.openid4vci.protocol.WalletAttestationTransport
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
    private val codec = MdocTestSupport.stack().codec

    private fun gateway(properties: OpenId4VciProperties = OpenId4VciProperties()) = SimulatedOpenId4VciGateway(
        properties,
        MdocDocTypeRegistry(),
        codec,
    )

    private fun proof(): ProofMaterial {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return ProofMaterial(keyId = "key-1", publicKey = kp.public as ECPublicKey, algorithm = "ES256")
    }

    private fun wia() = WalletAttestationTransport(
        jwt = "wia.jwt",
        popJwt = "wia.pop",
        cnfJkt = "jkt-test",
        expiresAt = java.time.Instant.now().plusSeconds(3600),
    )

    private fun ka(keyId: String = "key-1") = KeyAttestationTransport(
        jwt = "ka.jwt",
        keyId = keyId,
        attestedJkt = "proof-jkt",
        keyStorage = "iso_18045_high",
        certification = "test-cert",
        expiresAt = java.time.Instant.now().plusSeconds(3600),
        statusListUri = "/api/v1/wallet/status-lists/PRIMARY_LIST",
        statusListIndex = 10,
    )

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
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof(), wia())
        val authorized = gw.authorizeWithCode(sessionId, "code-123", prepared.state, wia())
        assertTrue(authorized.accessTokenPresent)
        assertEquals("jkt-test", authorized.accessTokenCnfJkt)

        val outcome = gw.requestCredential(sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"), proof(), ka())
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
        gw.prepareAuthorization(sessionId, offer, metadata, proof(), wia())
        assertThrows(IllegalArgumentException::class.java) {
            gw.authorizeWithCode(sessionId, "code-1", "wrong-state", wia())
        }
    }

    @Test
    fun `deferred outcome can be polled to completion`() {
        val props = OpenId4VciProperties().apply {
            simulator.alwaysDefer = true
            simulator.deferredPollsBeforeIssue = 2
        }
        val gw = SimulatedOpenId4VciGateway(props, MdocDocTypeRegistry(), codec)
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof(), wia())
        gw.authorizeWithCode(sessionId, "code-x", prepared.state, wia())
        val req = gw.requestCredential(sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"), proof(), ka())
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
        val outcome = gw.requestCredential(UUID.randomUUID().toString(), IssuanceRequest("pid_jwt"), proof(), null)
        assertTrue(outcome is IssuanceOutcome.Failed)
    }

    @Test
    fun `mdoc credential configuration is issued when requested`() {
        val gw = gateway()
        val metadata = gw.resolveMetadata("https://issuer.example", listOf("org.iso.18013.5.1.mDL"))
        val (offer, _) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["org.iso.18013.5.1.mDL"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof(), wia())
        gw.authorizeWithCode(sessionId, "c", prepared.state, wia())
        val outcome = gw.requestCredential(
            sessionId,
            IssuanceRequest(credentialConfigurationId = "org.iso.18013.5.1.mDL"),
            proof(),
            null,
        )
        assertTrue(outcome is IssuanceOutcome.Issued)
        val issued = (outcome as IssuanceOutcome.Issued).credentials.first()
        assertEquals(IssuanceCredentialFormat.MSO_MDOC, issued.format)
        assertTrue(codec.validateIssuerSigned(issued.rawPayload))
    }

    @Test
    fun `notify and discard drop adapter state`() {
        val gw = gateway()
        val sessionId = UUID.randomUUID().toString()
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
        )
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof(), wia())
        gw.authorizeWithCode(sessionId, "c", prepared.state, wia())
        gw.requestCredential(sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"), proof(), ka())
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
            gw.authorizeWithPreAuthorizedCode(sessionId, offer, metadata, proof(), txCode = null, walletAttestation = wia())
        }
    }

    @Test
    fun `wallet-initiated metadata returns the requested ids`() {
        val gw = gateway()
        val metadata = gw.resolveMetadata("https://issuer.example", listOf("a", "b"))
        assertEquals(2, metadata.credentialConfigurations.size)
        assertEquals(listOf("a", "b"), metadata.credentialConfigurations.map { it.id })
    }

    @Test
    fun `device-bound configuration fails when key attestation is missing`() {
        val gw = gateway()
        val (offer, metadata) = gw.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        val prepared = gw.prepareAuthorization(sessionId, offer, metadata, proof(), wia())
        gw.authorizeWithCode(sessionId, "code", prepared.state, wia())
        val outcome = gw.requestCredential(
            adapterSessionId = sessionId,
            request = IssuanceRequest(credentialConfigurationId = "pid_jwt"),
            proof = proof(),
            keyAttestation = null,
        )
        assertTrue(outcome is IssuanceOutcome.Failed)
        assertEquals("key_attestation_missing", (outcome as IssuanceOutcome.Failed).code)
    }
}
