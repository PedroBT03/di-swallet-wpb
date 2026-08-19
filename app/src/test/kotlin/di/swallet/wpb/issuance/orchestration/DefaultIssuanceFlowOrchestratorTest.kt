/**
 * Tests issuance flow orchestration, policy gates, and error handling.
 */

package di.swallet.wpb.issuance.orchestration

import di.swallet.wpb.config.ConsentProperties
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.consent.ConsentSessionGuard
import di.swallet.wpb.consent.ConsentTestSupport
import di.swallet.wpb.consent.IssuedCredentialPreviewParser
import di.swallet.wpb.consent.IssuanceConsentViewBuilder
import di.swallet.wpb.consent.PendingCredentialStore
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
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
import di.swallet.wpb.ka.attestation.KeyAttestationProvider
import di.swallet.wpb.ka.validation.KeyAttestationValidationException
import di.swallet.wpb.ka.validation.KeyAttestationValidationService
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.format.sdjwt.SdJwtService
import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.openid4vci.adapter.SimulatedOpenId4VciGateway
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.IssuanceConsentSubmission
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.wia.attestation.WalletAttestationProvider
import di.swallet.wpb.wia.status.WiaStatusManagementService
import di.swallet.wpb.wia.validation.DefaultWiaValidationService
import di.swallet.wpb.wia.validation.WiaPopValidationService
import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
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
    private val mdocCodec = MdocTestSupport.stack().codec

    /**
     * In-memory storage stub avoids JPA/H2 wiring for the unit tests.
     * The orchestrator's only contract with storage is "store the
     * issued credential and return its persistence id".
     */
    private class StubIssuedCredentialStorage : IssuedCredentialStorage {
        val stored = mutableListOf<IssuedCredential>()
        private val seq = java.util.concurrent.atomic.AtomicLong()

        /** Appends the credential to [stored] and returns a monotonically increasing fake persistence id. */
        override fun store(
            holderId: String,
            issued: IssuedCredential,
            walletKey: WalletKey?,
            keyAliasHint: String?,
            deviceBound: Boolean,
        ): Long {
            stored += issued
            return seq.incrementAndGet()
        }
    }

    private class StubWiaStatusManagementService : WiaStatusManagementService {
        private val map = mutableMapOf<String, Int>()
        private var seq = 200

        /** Allocates a stable status index per holder–issuer pair, reusing the same index on repeat lookups. */
        override fun getOrAllocateStatus(holderId: String, issuerId: String?) =
            di.swallet.wpb.issuance.domain.WiaStatusReference(
                listId = "PRIMARY_LIST",
                index = map.getOrPut("$holderId::${issuerId ?: "*"}") { seq++ },
                uri = "/api/v1/wallet/status-lists/PRIMARY_LIST",
            )

        /** No-op; revocation is not exercised in these orchestrator tests. */
        override fun revokeHolder(holderId: String) = Unit
    }

    private class StubWalletAttestationProvider(
        private val statusManagementService: WiaStatusManagementService,
    ) : WalletAttestationProvider {
        /** Builds a synthetic WIA with placeholder JWTs, a cnfJkt derived from the holder id, and a freshly allocated client status. */
        override fun issue(
            holderId: String,
            walletInstanceId: String,
            issuerId: String?,
        ): di.swallet.wpb.issuance.domain.WalletInstanceAttestation {
            val now = java.time.Instant.now()
            return di.swallet.wpb.issuance.domain.WalletInstanceAttestation(
                jwt = "wia.jwt.$holderId",
                popJwt = "",
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
        /** Returns a fixed ES256 KA JWT whose attestedJkt is the RFC 7638 thumbprint of the supplied proof public key. */
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

        /** Provisioning KA stub reusing the issuance stub with no issuer scope. */
        override fun issueForProvisioning(
            holderId: String,
            keyAlias: String,
            proofPublicKey: java.security.interfaces.ECPublicKey,
        ): KeyAttestation =
            issue(
                holderId = holderId,
                issuerId = null,
                metadata = ResolvedIssuerMetadata(credentialIssuerId = "https://provisioning.wpb.local"),
                configuration = CredentialConfigurationDescriptor(
                    id = "wallet_provisioning",
                    format = IssuanceCredentialFormat.SD_JWT_VC,
                    keyAttestationRequired = true,
                    proofTypesSupported = listOf("jwt", "attestation"),
                ),
                proofPublicKey = proofPublicKey,
                proofKeyId = keyAlias,
            )
    }

    /**
     * Wires a fully stubbed orchestrator with simulated gateway, in-memory session repo, and optional KA revocation simulation.
     * Returns the orchestrator together with its credential storage stub for post-condition assertions.
     */
    private fun orchestrator(
        properties: OpenId4VciProperties = OpenId4VciProperties(),
        kaRevoked: Boolean = false,
        consentProperties: ConsentProperties = ConsentTestSupport.properties(),
        storage: StubIssuedCredentialStorage = StubIssuedCredentialStorage(),
    ): Pair<DefaultIssuanceFlowOrchestrator, StubIssuedCredentialStorage> {
        val wiaStatus = StubWiaStatusManagementService()
        val validationService = object : KeyAttestationValidationService {
            /** Throws ka_revoked when [kaRevoked] is true; otherwise passes technical checks. */
            override fun validateTechnical(attestation: KeyAttestation, configuration: CredentialConfigurationDescriptor) {
                if (kaRevoked) {
                    throw KeyAttestationValidationException("ka_revoked", "key attestation status is revoked")
                }
            }

            /** Always succeeds; trust chain validation is out of scope for orchestrator unit tests. */
            override fun validateTrust(
                attestation: KeyAttestation,
                configuration: CredentialConfigurationDescriptor?,
                metadata: di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata?,
            ) = Unit

            /** Always succeeds; proof-to-KA binding is not exercised here. */
            override fun validateBinding(attestation: KeyAttestation, proof: di.swallet.wpb.issuance.proof.ProofMaterial) = Unit
        }
        val pendingStore = ConsentTestSupport.pendingCredentialStore()
        val orchestrator = DefaultIssuanceFlowOrchestrator(
            gateway = SimulatedOpenId4VciGateway(
                properties,
                MdocDocTypeRegistry(),
                mdocCodec,
                SdJwtService(ObjectMapper()),
                ObjectMapper(),
            ),
            repository = InMemoryIssuanceSessionRepository(),
            trustValidator = DefaultIssuerTrustValidator(properties, IssuerSignedMetadataValidator(properties)),
            policy = DefaultIssuancePolicy(properties),
            proofProvider = EphemeralProofMaterialProvider(),
            attestationProvider = StubWalletAttestationProvider(wiaStatus),
            wiaValidationService = DefaultWiaValidationService(
                org.mockito.Mockito.mock(di.swallet.wpb.service.StatusListService::class.java).also {
                    org.mockito.Mockito.`when`(it.isRevoked(org.mockito.ArgumentMatchers.anyInt())).thenReturn(false)
                },
            ),
            wiaPopValidationService = object : WiaPopValidationService {
                override fun validate(popJwt: String, attestation: WalletInstanceAttestation) = Unit
            },
            keyAttestationProvider = StubKeyAttestationProvider(),
            keyAttestationValidationService = validationService,
            credentialStorage = storage,
            keyBindingRuntimeService = mock(KeyBindingRuntimeService::class.java),
            eventStore = InMemoryIssuanceEventStore(),
            properties = properties,
            consentProperties = consentProperties,
            pendingCredentialStore = pendingStore,
            issuanceConsentViewBuilder = IssuanceConsentViewBuilder(
                consentProperties,
                pendingStore,
                IssuedCredentialPreviewParser(
                    SdJwtService(ObjectMapper()),
                    mock(MdocCredentialCodec::class.java),
                ),
            ),
            consentSessionGuard = ConsentSessionGuard(),
            transactionLogger = TransactionLogTestSupport.noopTransactionLogger(),
            wscaSciGrantService = di.swallet.wpb.security.WscaSciTestSupport.grantService(),
        )
        return orchestrator to storage
    }

    /** Submits granted issuance consent when the session is waiting at ISSUANCE_CONSENT_PENDING; otherwise returns the context unchanged. */
    private fun approveStorage(
        orch: DefaultIssuanceFlowOrchestrator,
        ctx: di.swallet.wpb.issuance.domain.IssuanceContext,
        holderId: String,
    ): di.swallet.wpb.issuance.domain.IssuanceContext {
        if (ctx.state != IssuanceState.ISSUANCE_CONSENT_PENDING) return ctx
        return orch.submitIssuanceConsent(
            ctx.sessionMeta.sessionId,
            IssuanceConsentSubmission(
                sessionId = ctx.sessionMeta.sessionId.toString(),
                holderId = holderId,
                granted = true,
            ),
        )
    }

    /** Issues WIA on the first call, then completes authorization preparation once PoP is supplied. */
    private fun DefaultIssuanceFlowOrchestrator.prepareWithPop(sessionId: java.util.UUID): di.swallet.wpb.issuance.domain.IssuanceContext {
        prepareAuthorization(sessionId)
        return prepareAuthorization(sessionId, walletAttestationPopJwt = "stub-pop")
    }

    /** Issues WIA on the first call, then completes pre-authorization once PoP is supplied. */
    private fun DefaultIssuanceFlowOrchestrator.completePreAuthorizedWithPop(
        sessionId: java.util.UUID,
        txCode: String?,
    ): di.swallet.wpb.issuance.domain.IssuanceContext {
        completePreAuthorizedCode(sessionId, txCode = null)
        return completePreAuthorizedCode(sessionId, txCode = txCode, walletAttestationPopJwt = "stub-pop")
    }

    private val offerByValue =
        """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}"""

    private val preAuthOffer =
        """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"],"grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"tx_code":{"length":4}}}}"""

    /**
     * Full authorization_code flow from offer resolution through consent approval ends in NOTIFIED with KA lifecycle events.
     */
    @Test
    fun `authorization_code happy path goes to NOTIFIED`() {
        val (orch, _) = orchestrator()
        var ctx = orch.resolveOffer(offerByValue, holderId = "holder-1")
        assertEquals(IssuanceState.OFFER_RESOLVED, ctx.state)
        assertNotNull(ctx.resolvedOffer)
        assertNotNull(ctx.issuerMetadata)
        assertTrue(ctx.trustDecision!!.trusted)
        assertTrue(ctx.policyDecision!!.allowed)

        ctx = orch.prepareWithPop(ctx.sessionMeta.sessionId)
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
        assertEquals(IssuanceState.ISSUANCE_CONSENT_PENDING, ctx.state)
        ctx = approveStorage(orch, ctx, "holder-1")
        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
        assertEquals(1, ctx.issuedCredentials.size)
        assertEquals("ka.generated", orchEvents(orch, ctx.sessionMeta.sessionId).firstOrNull { it == "ka.generated" })
        assertEquals("ka.attached", orchEvents(orch, ctx.sessionMeta.sessionId).firstOrNull { it == "ka.attached" })
        assertEquals("ka.validated", orchEvents(orch, ctx.sessionMeta.sessionId).firstOrNull { it == "ka.validated" })

        ctx = orch.notify(ctx.sessionMeta.sessionId, NotificationEvent.CREDENTIAL_ACCEPTED, null)
        assertEquals(IssuanceState.NOTIFIED, ctx.state)
    }

    /**
     * KA validation stub reports revoked status; credential request fails with ka_revoked and emits ka.revoked.
     */
    @Test
    fun `revoked key attestation fails issuance and emits ka revoked event`() {
        val (orch, _) = orchestrator(kaRevoked = true)
        var ctx = orch.resolveOffer(offerByValue, holderId = "holder-revoked")
        ctx = orch.prepareWithPop(ctx.sessionMeta.sessionId)
        ctx = orch.completeAuthorizationCode(ctx.sessionMeta.sessionId, "code", ctx.preparedAuthorization!!.state)
        val failed = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"))

        assertEquals(IssuanceState.FAILED, failed.state)
        assertEquals("ka_revoked", failed.error?.code)
        assertTrue(orchEvents(orch, failed.sessionMeta.sessionId).contains("ka.revoked"))
    }

    /**
     * Pre-authorized offer resolved without tx_code fails with pre_authorized_failed.
     */
    @Test
    fun `pre-authorized_code path requires tx_code`() {
        val (orch, _) = orchestrator()
        val ctx = orch.resolveOffer(preAuthOffer, holderId = "holder-2")
        assertEquals(IssuanceState.OFFER_RESOLVED, ctx.state)
        val failed = orch.completePreAuthorizedCode(ctx.sessionMeta.sessionId, txCode = null)
        assertEquals(IssuanceState.OFFER_RESOLVED, failed.state)
        assertNotNull(failed.wia?.attestation)
        val withoutTxCode = orch.completePreAuthorizedCode(
            failed.sessionMeta.sessionId,
            txCode = null,
            walletAttestationPopJwt = "stub-pop",
        )
        assertEquals(IssuanceState.FAILED, withoutTxCode.state)
        assertEquals("pre_authorized_failed", withoutTxCode.error?.code)
    }

    /**
     * Pre-authorized offer with tx_code reaches AUTHORIZED and, after consent, CREDENTIAL_ISSUED.
     */
    @Test
    fun `pre-authorized_code path with tx_code completes`() {
        val (orch, _) = orchestrator()
        var ctx = orch.resolveOffer(preAuthOffer, holderId = "holder-3")
        ctx = orch.completePreAuthorizedWithPop(ctx.sessionMeta.sessionId, txCode = "1234")
        assertEquals(IssuanceState.AUTHORIZED, ctx.state)
        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"))
        assertNull(ctx.error)
        assertEquals(IssuanceState.ISSUANCE_CONSENT_PENDING, ctx.state)
        ctx = approveStorage(orch, ctx, "holder-3")
        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
    }

    /**
     * academic_card configuration is not device-bound; issuance succeeds with KaState NOT_REQUIRED and no ka.generated event.
     */
    @Test
    fun `non-device bound configuration does not require KA`() {
        val offer =
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["academic_card"]}"""
        val (orch, _) = orchestrator()
        var ctx = orch.resolveOffer(offer, holderId = "holder-no-ka")
        ctx = orch.prepareWithPop(ctx.sessionMeta.sessionId)
        ctx = orch.completeAuthorizationCode(ctx.sessionMeta.sessionId, "c", ctx.preparedAuthorization!!.state)
        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "academic_card"))

        assertNull(ctx.error)
        assertEquals(IssuanceState.ISSUANCE_CONSENT_PENDING, ctx.state)
        ctx = approveStorage(orch, ctx, "holder-no-ka")
        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
        assertEquals(di.swallet.wpb.issuance.domain.KaState.NOT_REQUIRED, ctx.ka?.state)
        assertFalse(orchEvents(orch, ctx.sessionMeta.sessionId).contains("ka.generated"))
    }

    /**
     * Simulator always defers; polling twice reaches consent, then deferred issuance completes through NOTIFIED.
     */
    @Test
    fun `deferred path persists transaction id and resumes issuance`() {
        val (orch, _) = orchestrator(OpenId4VciProperties().apply {
            simulator.alwaysDefer = true
            simulator.deferredPollsBeforeIssue = 2
        })
        var ctx = orch.resolveOffer(offerByValue, holderId = "holder-deferred")
        ctx = orch.prepareWithPop(ctx.sessionMeta.sessionId)
        ctx = orch.completeAuthorizationCode(ctx.sessionMeta.sessionId, "c", ctx.preparedAuthorization!!.state)
        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest("pid_jwt"))
        assertNull(ctx.error)
        assertEquals(IssuanceState.DEFERRED_PENDING, ctx.state)
        assertNotNull(ctx.deferredHandle)

        // First poll: still pending
        ctx = orch.queryDeferred(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.DEFERRED_PENDING, ctx.state)

        // Second poll: awaiting storage consent
        ctx = orch.queryDeferred(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.ISSUANCE_CONSENT_PENDING, ctx.state)
        ctx = approveStorage(orch, ctx, "holder-deferred")
        assertEquals(IssuanceState.DEFERRED_ISSUED, ctx.state)
        assertEquals(1, ctx.issuedCredentials.size)

        ctx = orch.notify(ctx.sessionMeta.sessionId, NotificationEvent.CREDENTIAL_ACCEPTED, null)
        assertEquals(IssuanceState.NOTIFIED, ctx.state)
    }

    /**
     * Issuer not on the allow-list stops at REJECTED with trustDecision.trusted=false.
     */
    @Test
    fun `untrusted issuer rejects with REJECTED terminal`() {
        val (orch, _) = orchestrator(OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = "https://another-issuer"
        })
        val ctx = orch.resolveOffer(offerByValue, holderId = "holder-untrusted")
        assertEquals(IssuanceState.REJECTED, ctx.state)
        assertFalse(ctx.trustDecision!!.trusted)
    }

    /**
     * mDL offer with allowMdoc=false yields policyDecision.allowed=false at resolution.
     */
    @Test
    fun `mdoc policy block rejects offer`() {
        val (orch, _) = orchestrator(OpenId4VciProperties().apply {
            policy.allowMdoc = false
        })
        val mdocOffer =
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["org.iso.18013.5.1.mDL"]}"""
        val ctx = orch.resolveOffer(mdocOffer, holderId = "holder-policy")
        assertFalse(ctx.policyDecision!!.allowed)
    }

    /**
     * requestCredential immediately after offer resolution throws mentioning AUTHORIZED state.
     */
    @Test
    fun `cannot request credential before authorization`() {
        val (orch, _) = orchestrator()
        val ctx = orch.resolveOffer(offerByValue, holderId = "holder-x")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest("pid_jwt"))
        }
        assertTrue(ex.message!!.contains("AUTHORIZED"))
    }

    /**
     * prepareAuthorization on a pre-authorized offer fails with invalid_flow.
     */
    @Test
    fun `cannot prepare authorization for pre-authorized offer`() {
        val (orch, _) = orchestrator()
        var ctx = orch.resolveOffer(preAuthOffer, holderId = "holder-x")
        ctx = orch.prepareAuthorization(ctx.sessionMeta.sessionId)
        assertEquals(IssuanceState.FAILED, ctx.state)
        assertEquals("invalid_flow", ctx.error?.code)
    }

    /**
     * getSession for a random unknown id throws ResponseStatusException (404-equivalent).
     */
    @Test
    fun `getSession returns null-equivalent via 404 for unknown id`() {
        val (orch, _) = orchestrator()
        assertThrows(ResponseStatusException::class.java) {
            orch.getSession(java.util.UUID.randomUUID())
        }
    }

    /**
     * Denied issuance consent moves to REJECTED and leaves stub storage empty.
     */
    @Test
    fun `rejecting issuance consent does not persist credential`() {
        val storage = StubIssuedCredentialStorage()
        val (orch, stubStorage) = orchestrator(storage = storage)
        var ctx = orch.resolveOffer(offerByValue, holderId = "holder-reject")
        ctx = orch.prepareWithPop(ctx.sessionMeta.sessionId)
        ctx = orch.completeAuthorizationCode(ctx.sessionMeta.sessionId, "code", ctx.preparedAuthorization!!.state)
        ctx = orch.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"))
        assertEquals(IssuanceState.ISSUANCE_CONSENT_PENDING, ctx.state)

        val rejected = orch.submitIssuanceConsent(
            ctx.sessionMeta.sessionId,
            IssuanceConsentSubmission(
                sessionId = ctx.sessionMeta.sessionId.toString(),
                holderId = "holder-reject",
                granted = false,
                reason = "do not store",
            ),
        )
        assertEquals(IssuanceState.REJECTED, rejected.state)
        assertTrue(stubStorage.stored.isEmpty())
    }

    /**
     * getSession after resolveOffer returns the same session id, state, and no error.
     */
    @Test
    fun `getSession returns persisted snapshot`() {
        val (orch, _) = orchestrator()
        val ctx = orch.resolveOffer(offerByValue, holderId = "holder-snap")
        val snap = orch.getSession(ctx.sessionMeta.sessionId)
        assertEquals(ctx.state, snap.state)
        assertEquals(ctx.sessionMeta.sessionId, snap.sessionMeta.sessionId)
        assertNull(snap.error)
    }

    /** Reflects into the orchestrator's in-memory event store and returns the ordered event type strings for a session. */
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
