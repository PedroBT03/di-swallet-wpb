package di.swallet.wpb.issuance.orchestration

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.domain.KeyAttestation
import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.issuance.persistence.InMemoryIssuanceSessionRepository
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.issuance.policy.DefaultIssuancePolicy
import di.swallet.wpb.issuance.proof.EphemeralProofMaterialProvider
import di.swallet.wpb.issuance.storage.IssuedCredentialStorage
import di.swallet.wpb.issuance.trust.DefaultIssuerTrustValidator
import di.swallet.wpb.issuance.trust.IssuerSignedMetadataValidator
import di.swallet.wpb.observability.InMemoryIssuanceEventStore
import di.swallet.wpb.ka.attestation.KeyAttestationProvider
import di.swallet.wpb.ka.validation.KeyAttestationValidationException
import di.swallet.wpb.ka.validation.KeyAttestationValidationService
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocIsoRuntimeService
import di.swallet.wpb.openid4vci.adapter.SimulatedOpenId4VciGateway
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.wia.attestation.WalletAttestationProvider
import di.swallet.wpb.wia.status.WiaStatusManagementService
import di.swallet.wpb.wia.validation.DefaultWiaValidationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import di.swallet.wpb.service.KeyBindingRuntimeService
import org.mockito.Mockito.mock

class DefaultIssuanceFlowOrchestratorTest {
    private val mdocCodec = MdocCredentialCodec(MdocIsoRuntimeService())

    /**
     * In-memory storage stub avoids JPA/H2 wiring for the unit tests.
     * The Phase 2 orchestrator's only contract with storage is "store the
     * issued credential and return its persistence id".
     */
    private class StubIssuedCredentialStorage : IssuedCredentialStorage {
        private val seq = java.util.concurrent.atomic.AtomicLong()
        override fun store(
            holderId: String,
            issued: IssuedCredential,
            walletKey: WalletKey?,
            keyAliasHint: String?,
        ): Long =
            seq.incrementAndGet()
    }

    private class StubWiaStatusManagementService : WiaStatusManagementService {
        private val map = mutableMapOf<String, Int>()
        private var seq = 200
        override fun getOrAllocateStatus(holderId: String, issuerId: String?) =
            di.swallet.wpb.issuance.domain.WiaStatusReference(
                listId = "PRIMARY_LIST",
                index = map.getOrPut("$holderId::${issuerId ?: "*"}") { seq++ },
                uri = "/api/v1/wallet/status-lists/PRIMARY_LIST",
            )
        override fun revokeHolder(holderId: String) = Unit
    }

    private class StubWalletAttestationProvider(
        private val statusManagementService: WiaStatusManagementService,
    ) : WalletAttestationProvider {
        override fun issue(
            holderId: String,
            walletInstanceId: String,
            issuerId: String?,
        ): di.swallet.wpb.issuance.domain.WalletInstanceAttestation {
            val now = java.time.Instant.now()
            return di.swallet.wpb.issuance.domain.WalletInstanceAttestation(
                jwt = "wia.jwt.$holderId",
                popJwt = "wia.pop.$holderId",
                walletInstanceId = walletInstanceId,
                walletName = "DI-Swallet-WPB",
                walletVersion = "test",
                walletSolutionCertificationInformation = "test-cert",
                cnfJkt = "jkt-$holderId",
                clientStatus = statusManagementService.getOrAllocateStatus(holderId, issuerId),
                tokenExpiresAt = now.plusSeconds(3600),
                clientStatusExpiresAt = now.plusSeconds(31L * 24 * 3600),
                issuedAt = now,
                issuerScope = issuerId,
            )
        }
    }

    private class StubKeyAttestationProvider : KeyAttestationProvider {
        override fun issue(
            holderId: String,
            issuerId: String?,
            metadata: ResolvedIssuerMetadata,
            configuration: CredentialConfigurationDescriptor,
            proofPublicKey: java.security.interfaces.ECPublicKey,
            proofKeyId: String,
        ): KeyAttestation {
            val now = java.time.Instant.now()
            val attestedJkt = Rfc7638JwkThumbprint.fromEcPublicKey(proofPublicKey)
            return KeyAttestation(
                jwt = "eyJhbGciOiJFUzI1NiIsInR5cCI6ImtleWF0dGVzdGF0aW9uK2p3dCJ9.eyJpc3MiOiJkaWQ6d2ViOnRlc3Qud3BiIiwic3ViIjoiaG9sZGVyIiwiYXVkIjoiaXNzdWVyIiwiaWF0IjoxNzc5OTg0OTM4LCJleHAiOjQwNzA5MDg4MDB9.c2ln",
                keyId = proofKeyId,
                keyStorage = "iso_18045_high",
                certification = "test-cert",
                attestedJkt = attestedJkt,
                status = KaStatusReference(
                    listId = "PRIMARY_LIST",
                    index = 300,
                    uri = "/api/v1/wallet/status-lists/PRIMARY_LIST",
                ),
                tokenExpiresAt = now.plusSeconds(3600),
                statusExpiresAt = now.plusSeconds(31L * 24 * 3600),
                issuedAt = now,
                issuerScope = issuerId,
            )
        }
    }

    private fun orchestrator(
        properties: OpenId4VciProperties = OpenId4VciProperties(),
        kaRevoked: Boolean = false,
    ): DefaultIssuanceFlowOrchestrator {
        val wiaStatus = StubWiaStatusManagementService()
        val validationService = object : KeyAttestationValidationService {
            override fun validateTechnical(attestation: KeyAttestation, configuration: CredentialConfigurationDescriptor) {
                if (kaRevoked) {
                    throw KeyAttestationValidationException("ka_revoked", "key attestation status is revoked")
                }
            }

            override fun validateTrust(
                attestation: KeyAttestation,
                configuration: CredentialConfigurationDescriptor?,
                metadata: di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata?,
            ) = Unit

            override fun validateBinding(attestation: KeyAttestation, proof: di.swallet.wpb.issuance.proof.ProofMaterial) = Unit
        }
        return DefaultIssuanceFlowOrchestrator(
            gateway = SimulatedOpenId4VciGateway(
                properties,
                MdocDocTypeRegistry(),
                mdocCodec,
            ),
            repository = InMemoryIssuanceSessionRepository(),
            trustValidator = DefaultIssuerTrustValidator(properties, IssuerSignedMetadataValidator(properties)),
            policy = DefaultIssuancePolicy(properties),
            proofProvider = EphemeralProofMaterialProvider(),
            attestationProvider = StubWalletAttestationProvider(wiaStatus),
            wiaValidationService = DefaultWiaValidationService(),
            keyAttestationProvider = StubKeyAttestationProvider(),
            keyAttestationValidationService = validationService,
            credentialStorage = StubIssuedCredentialStorage(),
            keyBindingRuntimeService = mock(KeyBindingRuntimeService::class.java),
            eventStore = InMemoryIssuanceEventStore(),
            properties = properties,
        )
    }

    private val offerByValue =
        """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}"""

    private val preAuthOffer =
        """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"],"grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"tx_code":{"length":4}}}}"""

    @Test
    fun `authorization_code happy path goes to NOTIFIED`() {
        val orch = orchestrator()
        var ctx = orch.resolveOffer(offerByValue, holderId = "holder-1")
        assertEquals(IssuanceState.OFFER_RESOLVED, ctx.state)
        assertNotNull(ctx.resolvedOffer)
        assertNotNull(ctx.issuerMetadata)
        assertTrue(ctx.trustDecision!!.trusted)
        assertTrue(ctx.policyDecision!!.allowed)

        ctx = orch.prepareAuthorization(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.AUTHORIZATION_PREPARED, ctx.state)
        assertNotNull(ctx.preparedAuthorization)

        ctx = orch.completeAuthorizationCode(
            ctx.sessionMeta.sessionId,
            authorizationCode = "code-abc",
            state = ctx.preparedAuthorization!!.state,
        )
        assertEquals(IssuanceState.AUTHORIZED, ctx.state)

        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"))
        assertNull(ctx.error)
        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
        assertEquals(1, ctx.issuedCredentials.size)
        assertEquals("ka.generated", orchEvents(orch, ctx.sessionMeta.sessionId).firstOrNull { it == "ka.generated" })
        assertEquals("ka.attached", orchEvents(orch, ctx.sessionMeta.sessionId).firstOrNull { it == "ka.attached" })
        assertEquals("ka.validated", orchEvents(orch, ctx.sessionMeta.sessionId).firstOrNull { it == "ka.validated" })

        ctx = orch.notify(ctx.sessionMeta.sessionId, NotificationEvent.CREDENTIAL_ACCEPTED, null)
        assertEquals(IssuanceState.NOTIFIED, ctx.state)
    }

    @Test
    fun `revoked key attestation fails issuance and emits ka revoked event`() {
        val orch = orchestrator(kaRevoked = true)
        var ctx = orch.resolveOffer(offerByValue, holderId = "holder-revoked")
        ctx = orch.prepareAuthorization(ctx.sessionMeta.sessionId)
        ctx = orch.completeAuthorizationCode(ctx.sessionMeta.sessionId, "code", ctx.preparedAuthorization!!.state)
        val failed = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"))

        assertEquals(IssuanceState.FAILED, failed.state)
        assertEquals("ka_revoked", failed.error?.code)
        assertTrue(orchEvents(orch, failed.sessionMeta.sessionId).contains("ka.revoked"))
    }

    @Test
    fun `pre-authorized_code path requires tx_code`() {
        val orch = orchestrator()
        val ctx = orch.resolveOffer(preAuthOffer, holderId = "holder-2")
        assertEquals(IssuanceState.OFFER_RESOLVED, ctx.state)
        val failed = orch.completePreAuthorizedCode(ctx.sessionMeta.sessionId, txCode = null)
        assertEquals(IssuanceState.FAILED, failed.state)
        assertEquals("pre_authorized_failed", failed.error?.code)
    }

    @Test
    fun `pre-authorized_code path with tx_code completes`() {
        val orch = orchestrator()
        var ctx = orch.resolveOffer(preAuthOffer, holderId = "holder-3")
        ctx = orch.completePreAuthorizedCode(ctx.sessionMeta.sessionId, txCode = "1234")
        assertEquals(IssuanceState.AUTHORIZED, ctx.state)
        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"))
        assertNull(ctx.error)
        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
    }

    @Test
    fun `non-device bound configuration does not require KA`() {
        val offer =
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["academic_card"]}"""
        val orch = orchestrator()
        var ctx = orch.resolveOffer(offer, holderId = "holder-no-ka")
        ctx = orch.prepareAuthorization(ctx.sessionMeta.sessionId)
        ctx = orch.completeAuthorizationCode(ctx.sessionMeta.sessionId, "c", ctx.preparedAuthorization!!.state)
        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "academic_card"))

        assertNull(ctx.error)
        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
        assertEquals(di.swallet.wpb.issuance.domain.KaState.NOT_REQUIRED, ctx.ka?.state)
        assertFalse(orchEvents(orch, ctx.sessionMeta.sessionId).contains("ka.generated"))
    }

    @Test
    fun `deferred path persists transaction id and resumes issuance`() {
        val orch = orchestrator(OpenId4VciProperties().apply {
            simulator.alwaysDefer = true
            simulator.deferredPollsBeforeIssue = 2
        })
        var ctx = orch.resolveOffer(offerByValue, holderId = "holder-deferred")
        ctx = orch.prepareAuthorization(ctx.sessionMeta.sessionId)
        ctx = orch.completeAuthorizationCode(ctx.sessionMeta.sessionId, "c", ctx.preparedAuthorization!!.state)
        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest("pid_jwt"))
        assertNull(ctx.error)
        assertEquals(IssuanceState.DEFERRED_PENDING, ctx.state)
        assertNotNull(ctx.deferredHandle)

        // First poll: still pending
        ctx = orch.queryDeferred(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.DEFERRED_PENDING, ctx.state)

        // Second poll: issued
        ctx = orch.queryDeferred(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.DEFERRED_ISSUED, ctx.state)
        assertEquals(1, ctx.issuedCredentials.size)

        ctx = orch.notify(ctx.sessionMeta.sessionId, NotificationEvent.CREDENTIAL_ACCEPTED, null)
        assertEquals(IssuanceState.NOTIFIED, ctx.state)
    }

    @Test
    fun `untrusted issuer rejects with REJECTED terminal`() {
        val orch = orchestrator(OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = "https://another-issuer"
        })
        val ctx = orch.resolveOffer(offerByValue, holderId = "holder-untrusted")
        assertEquals(IssuanceState.REJECTED, ctx.state)
        assertFalse(ctx.trustDecision!!.trusted)
    }

    @Test
    fun `mdoc policy block rejects offer`() {
        val orch = orchestrator(OpenId4VciProperties().apply {
            policy.allowMdoc = false
        })
        val mdocOffer =
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["org.iso.18013.5.1.mDL"]}"""
        val ctx = orch.resolveOffer(mdocOffer, holderId = "holder-policy")
        assertFalse(ctx.policyDecision!!.allowed)
    }

    @Test
    fun `cannot request credential before authorization`() {
        val orch = orchestrator()
        val ctx = orch.resolveOffer(offerByValue, holderId = "holder-x")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest("pid_jwt"))
        }
        assertTrue(ex.message!!.contains("AUTHORIZED"))
    }

    @Test
    fun `cannot prepare authorization for pre-authorized offer`() {
        val orch = orchestrator()
        var ctx = orch.resolveOffer(preAuthOffer, holderId = "holder-x")
        ctx = orch.prepareAuthorization(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.FAILED, ctx.state)
        assertEquals("invalid_flow", ctx.error?.code)
    }

    @Test
    fun `getSession returns null-equivalent via 404 for unknown id`() {
        val orch = orchestrator()
        assertThrows(ResponseStatusException::class.java) {
            orch.getSession(java.util.UUID.randomUUID())
        }
    }

    @Test
    fun `getSession returns persisted snapshot`() {
        val orch = orchestrator()
        val ctx = orch.resolveOffer(offerByValue, holderId = "holder-snap")
        val snap = orch.getSession(ctx.sessionMeta.sessionId)
        assertEquals(ctx.state, snap.state)
        assertEquals(ctx.sessionMeta.sessionId, snap.sessionMeta.sessionId)
        assertNull(snap.error)
    }

    private fun orchEvents(
        orchestrator: DefaultIssuanceFlowOrchestrator,
        sessionId: java.util.UUID,
    ): List<String> {
        val field = DefaultIssuanceFlowOrchestrator::class.java.getDeclaredField("eventStore")
        field.isAccessible = true
        val store = field.get(orchestrator) as InMemoryIssuanceEventStore
        return store.getEvents(sessionId).map { it.type }
    }
}
