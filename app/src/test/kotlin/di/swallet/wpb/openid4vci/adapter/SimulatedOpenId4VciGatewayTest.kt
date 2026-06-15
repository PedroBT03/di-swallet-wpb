/**
 * Tests simulated open id4 vci gateway.
 */

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

    /** Simulated gateway wired with the given properties, doc-type registry, and shared mdoc codec. */
    private fun gateway(properties: OpenId4VciProperties = OpenId4VciProperties()) = SimulatedOpenId4VciGateway(
        properties,
        MdocDocTypeRegistry(),
        codec,
    )

    /** Fixed ES256 proof material with key id "key-1" for simulated issuance calls. */
    private fun proof(): ProofMaterial {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return ProofMaterial(keyId = "key-1", publicKey = kp.public as ECPublicKey, algorithm = "ES256")
    }

    /** Placeholder wallet attestation transport accepted by the simulated gateway. */
    private fun wia() = WalletAttestationTransport(
        jwt = "wia.jwt",
        popJwt = "wia.pop",
        cnfJkt = "jkt-test",
        expiresAt = java.time.Instant.now().plusSeconds(3600),
    )

    /** Placeholder key attestation transport whose key id defaults to "key-1" unless overridden. */
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

    /**
     * Credential offer URI embeds an authorization_code grant without pre-authorized_code.
     * resolveOffer returns AUTHORIZATION_CODE flow with issuer id and pid_jwt configuration ids.
     */
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

    /**
     * Offer grant includes pre-authorized_code with a required tx_code length.
     * resolveOffer selects PRE_AUTHORIZED_CODE and marks txCodeRequired on the grant.
     */
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

    /**
     * Authorization code flow runs prepare, authorize, and credential request with key attestation.
     * Access token is present and the issued SD-JWT VC payload contains disclosure separators.
     */
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

    /**
     * Authorization is prepared then authorizeWithCode is called with a mismatched state value.
     * Gateway throws IllegalArgumentException.
     */
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

    /**
     * Simulator defers issuance for two poll cycles before completing.
     * First queryDeferred is StillPending; the second returns Issued.
     */
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

    /**
     * requestCredential is invoked for a fresh session without prior authorization.
     * Outcome is Failed rather than Issued or Deferred.
     */
    @Test
    fun `requestCredential without authorization returns failed outcome`() {
        val gw = gateway()
        val outcome = gw.requestCredential(UUID.randomUUID().toString(), IssuanceRequest("pid_jwt"), proof(), null)
        assertTrue(outcome is IssuanceOutcome.Failed)
    }

    /**
     * Offer targets the org.iso.18013.5.1.mDL configuration through the authorization code path.
     * Issued credential uses MSO_MDOC format and passes issuer-signed validation.
     */
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

    /**
     * Session completes issuance then notify and discard are called.
     * Subsequent queryDeferred on the same session returns Failed after state is cleared.
     */
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

    /**
     * Pre-authorized offer requires tx_code but authorizeWithPreAuthorizedCode passes null.
     * Gateway throws IllegalArgumentException.
     */
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

    /**
     * resolveMetadata is called with two configuration ids for wallet-initiated discovery.
     * Returned metadata lists both configurations by id.
     */
    @Test
    fun `wallet-initiated metadata returns the requested ids`() {
        val gw = gateway()
        val metadata = gw.resolveMetadata("https://issuer.example", listOf("a", "b"))
        assertEquals(2, metadata.credentialConfigurations.size)
        assertEquals(listOf("a", "b"), metadata.credentialConfigurations.map { it.id })
    }

    /**
     * pid_jwt configuration requires key attestation but requestCredential passes null KA.
     * Outcome is Failed with code key_attestation_missing.
     */
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
