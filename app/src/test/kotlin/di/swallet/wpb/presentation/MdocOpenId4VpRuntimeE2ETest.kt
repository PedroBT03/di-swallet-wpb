/**
 * End-to-end tests for mdoc open id4 vp runtime.
 */

package di.swallet.wpb.presentation

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.testWalletProperties
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.issuance.storage.IssuedCredentialSupersessionService
import di.swallet.wpb.issuance.storage.JpaIssuedCredentialStorage
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocEffectiveDocTypeResolver
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.format.sdjwt.SdJwtService
import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.observability.InMemorySessionEventStore
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
import di.swallet.wpb.openid4vci.adapter.SimulatedOpenId4VciGateway
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.KeyAttestationTransport
import di.swallet.wpb.openid4vci.protocol.WalletAttestationTransport
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.RegistryDecision
import di.swallet.wpb.presentation.domain.TrustDecision
import di.swallet.wpb.presentation.domain.VpToken
import di.swallet.wpb.presentation.format.DefaultVpTokenBuilder
import di.swallet.wpb.format.sdjwt.KeyBindingJwtSigner
import di.swallet.wpb.presentation.format.MdocVpBuilder
import di.swallet.wpb.format.sdjwt.SdJwtVpBuilder
import di.swallet.wpb.presentation.matching.DefaultCredentialMatcher
import di.swallet.wpb.consent.ConsentTestSupport
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import di.swallet.wpb.presentation.orchestration.DefaultPresentationFlowOrchestrator
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import di.swallet.wpb.presentation.policy.DefaultPolicyEngine
import di.swallet.wpb.presentation.registry.RegistryValidator
import di.swallet.wpb.presentation.trust.TrustValidator
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.service.KeyBindingRuntimeService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@ConformanceTest
class MdocOpenId4VpRuntimeE2ETest {
    private val holder = MdocTestSupport.holderBinding()
    private val stack = MdocTestSupport.stack(holderBindings = listOf(holder))
    private val mdocRegistry = MdocDocTypeRegistry()
    private val mdocCodec = stack.codec

    /**
     * PID and mDL mdocs are issued into the wallet then presented through the full orchestrator.
     * Positive flows dispatch valid device responses; an empty-wallet negative request ends policy_rejected.
     */
    @Test
    @ConformanceScenario("vp_mdoc_runtime_e2e")
    fun `mdoc runtime supports PID and mDL positive and negative`() = runBlocking {
        val storedCredentials = mutableListOf<WalletCredential>()
        val credentialRepository = inMemoryRepository(storedCredentials)
        val storage = JpaIssuedCredentialStorage(
            repository = credentialRepository,
            walletKeyRepository = stack.walletKeyRepository,
            disclosureCipher = DisclosureCipherService(testWalletProperties()),
            mdocCredentialCodec = mdocCodec,
            mdocDocTypeRegistry = mdocRegistry,
            keyBindingRuntimeService = mock(KeyBindingRuntimeService::class.java),
            credentialStatusParser = di.swallet.wpb.revocation.RevocationTestSupport.credentialStatusParser(),
            supersessionService = IssuedCredentialSupersessionService(
                credentialRepository,
                di.swallet.wpb.revocation.RevocationTestSupport.noopGuard(),
                mock(di.swallet.wpb.service.StatusListService::class.java),
            ),
        )

        val issuanceGateway = SimulatedOpenId4VciGateway(
            properties = OpenId4VciProperties(),
            mdocDocTypeRegistry = mdocRegistry,
            mdocCredentialCodec = mdocCodec,
            sdJwtService = SdJwtService(ObjectMapper()),
            objectMapper = ObjectMapper(),
        )
        issueAndStore(
            gateway = issuanceGateway,
            storage = storage,
            holderId = "holder-1",
            configurationId = "eu.europa.ec.eudi.pid.1",
            keyAttestation = keyAttestation(), // PID path requires KA in simulated profile.
        )
        issueAndStore(
            gateway = issuanceGateway,
            storage = storage,
            holderId = "holder-1",
            configurationId = "org.iso.18013.5.1.mDL",
            keyAttestation = null,
        )

        val gateway = PresentationGatewayStub()
        val orchestrator = orchestrator(
            gateway = gateway,
            repository = credentialRepository,
        )

        val pidPositive = startAndConsent(
            orchestrator = orchestrator,
            request = requestFor(
                queryId = "pid",
                docType = "eu.europa.ec.eudi.pid.1",
                claim = "given_name",
            ),
        )
        assertEquals(PresentationState.DISPATCHED, pidPositive.state)
        val pidPresentation = gateway.lastPositiveToken!!.presentationsByQueryId["pid"]!!.single()
        val pidDecoded = mdocCodec.decode(pidPresentation)
        assertNotNull(pidDecoded)
        assertEquals("eu.europa.ec.eudi.pid.1", pidDecoded!!.docType)
        assertTrue(pidDecoded.claims.containsKey("eu.europa.ec.eudi.pid.1.given_name"))
        assertTrue(mdocCodec.validateDeviceResponse(pidPresentation))

        val mdlPositive = startAndConsent(
            orchestrator = orchestrator,
            request = requestFor(
                queryId = "mdl",
                docType = "org.iso.18013.5.1.mDL",
                claim = "driving_privileges",
            ),
        )
        assertEquals(PresentationState.DISPATCHED, mdlPositive.state)
        val mdlPresentation = gateway.lastPositiveToken!!.presentationsByQueryId["mdl"]!!.single()
        val mdlDecoded = mdocCodec.decode(mdlPresentation)
        assertNotNull(mdlDecoded)
        assertEquals("org.iso.18013.5.1.mDL", mdlDecoded!!.docType)
        assertTrue(mdlDecoded.claims.containsKey("org.iso.18013.5.1.driving_privileges"))
        assertTrue(mdocCodec.validateDeviceResponse(mdlPresentation))

        val negative = orchestrator.startSession(
            requestUri = "http://verifier/request-negative",
            holderId = "holder-1",
        )
        assertEquals(PresentationState.DISPATCHED, negative.state)
        assertEquals("policy_rejected", negative.error?.code)
    }

    /** Assembles a full orchestrator with real mdoc VP builder, permissive trust/registry stubs, and the given gateway. */
    private fun orchestrator(
        gateway: PresentationGatewayStub,
        repository: WalletCredentialRepository,
    ): DefaultPresentationFlowOrchestrator {
        val matcher = PresentationTestSupport.credentialMatcher(
            repository,
            mdocCodec,
            mdocRegistry,
            demoMode = false,
        )
        val sdJwtBuilder = SdJwtVpBuilder(
            walletCredentialRepository = repository,
            disclosureCipherService = DisclosureCipherService(testWalletProperties()),
            disclosureSelector = PresentationTestSupport.disclosureSelector,
            keyBindingJwtSigner = object : KeyBindingJwtSigner {
                /** Returns a fixed KB-JWT stub so SD-JWT VP assembly does not require a real signing key. */
                override fun signKeyBindingJwt(userId: String, payload: Map<String, Any>): String = "kb.jwt.stub"
            },
        )
        val vpBuilder = DefaultVpTokenBuilder(
            sdJwtVpBuilder = sdJwtBuilder,
            mdocVpBuilder = MdocVpBuilder(
                repository,
                mdocCodec,
                mdocRegistry,
                MdocEffectiveDocTypeResolver(mdocRegistry),
            ),
        )
        val consentDeps = ConsentTestSupport.presentationOrchestratorDeps(repository = repository)
        return DefaultPresentationFlowOrchestrator(
            gateway = gateway,
            repository = InMemoryPresentationSessionRepository(),
            trustValidator = object : TrustValidator {
                /** Marks every verifier as trusted so mdoc matching and VP building can be exercised in isolation. */
                override fun validate(context: PresentationContext): PresentationContext =
                    context.copy(trustDecision = TrustDecision(trusted = true, reason = "mdoc-runtime-e2e"))
            },
            registryValidator = object : RegistryValidator {
                /** Accepts registry validation with intendedUseChecked so policy passes without a real TS5 lookup. */
                override fun validate(context: PresentationContext): PresentationContext =
                    context.copy(
                        registryDecision = RegistryDecision(
                            accepted = true,
                            reason = "mdoc-runtime-e2e",
                            rpIdentifier = context.authorizationRequest?.clientId,
                            sourceEndpoint = "/wrp/check-intended-use",
                            intendedUseChecked = true,
                        ),
                    )
            },
            policyEngine = DefaultPolicyEngine(OpenId4VpProperties().apply { demoMode = false }),
            credentialMatcher = matcher,
            vpTokenBuilder = vpBuilder,
            eventStore = InMemorySessionEventStore(),
            transactionLogger = TransactionLogTestSupport.noopTransactionLogger(),
            consentViewBuilder = consentDeps.consentViewBuilder,
            consentCredentialSelector = consentDeps.consentCredentialSelector,
            consentSessionGuard = consentDeps.consentSessionGuard,
            consentAuditRecorder = consentDeps.consentAuditRecorder,
            minimizationEvaluator = consentDeps.minimizationEvaluator,
            wpbMetrics = WpbMetricsTestSupport.noop(),
        wscaSciGrantService = di.swallet.wpb.security.WscaSciTestSupport.grantService(),
        )
    }

    /** Starts a session, asserts consent pending, grants all candidates, and returns the dispatched context. */
    private suspend fun startAndConsent(
        orchestrator: DefaultPresentationFlowOrchestrator,
        request: ResolvedAuthorizationRequest,
    ): PresentationContext {
        val session = orchestrator.startSession(request.requestUri, "holder-1")
        assertEquals(PresentationState.CONSENT_PENDING, session.state)
        val selected = session.credentialCandidates.map { it.candidateId }
        return orchestrator.submitConsent(
            sessionId = session.sessionMeta.sessionId,
            decision = ConsentSubmission(
                sessionId = session.sessionMeta.sessionId.toString(),
                holderId = "holder-1",
                granted = true,
                selectedCredentialIds = selected,
            ),
        )
    }

    /** Builds a ResolvedAuthorizationRequest with an mso_mdoc DCQL query for the given doc type and claim. */
    private fun requestFor(queryId: String, docType: String, claim: String): ResolvedAuthorizationRequest {
        return ResolvedAuthorizationRequest(
            requestToken = "rt-$queryId",
            requestUri = "http://verifier/request-$queryId",
            clientId = "verifier-mdoc-$queryId",
            responseMode = PresentationResponseMode.DIRECT_POST,
            nonce = "nonce-$queryId",
            state = "state-$queryId",
            requirements = PresentationRequirements(
                dcqlQueryJson = """{"credentials":[{"id":"$queryId","format":"mso_mdoc","meta":{"doctype_values":["$docType"]},"claims":[{"path":["$claim"]}]}]}""",
                credentialQueryIds = emptyList(),
                requestedFormats = setOf(CredentialFormat.MDOC),
            ),
        )
    }

    /**
     * Runs the simulated OpenID4VCI issuance flow for one configuration id and persists
     * the issued credential into JpaIssuedCredentialStorage for the holder.
     */
    private fun issueAndStore(
        gateway: SimulatedOpenId4VciGateway,
        storage: JpaIssuedCredentialStorage,
        holderId: String,
        configurationId: String,
        keyAttestation: KeyAttestationTransport?,
    ) {
        val (offer, metadata) = gateway.resolveOffer(
            """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["$configurationId"]}""",
        )
        val sessionId = UUID.randomUUID().toString()
        val prepared = gateway.prepareAuthorization(sessionId, offer, metadata, holder.proof, walletAttestation())
        gateway.authorizeWithCode(sessionId, "code-$configurationId", prepared.state, walletAttestation())
        val outcome = gateway.requestCredential(
            adapterSessionId = sessionId,
            request = IssuanceRequest(credentialConfigurationId = configurationId),
            proof = holder.proof,
            keyAttestation = keyAttestation,
        )
        assertTrue(outcome is IssuanceOutcome.Issued)
        val issued = (outcome as IssuanceOutcome.Issued).credentials.single()
        storage.store(holderId = holderId, issued = issued, walletKey = holder.walletKey)
    }

    /** Mockito-backed repository that assigns sequential ids and mirrors saves into the supplied mutable list. */
    private fun inMemoryRepository(state: MutableList<WalletCredential>): WalletCredentialRepository {
        val repository = mock(WalletCredentialRepository::class.java)
        val seq = AtomicLong(0)
        doAnswer { invocation ->
            val incoming = invocation.getArgument<WalletCredential>(0)
            val persisted = WalletCredential(
                id = seq.incrementAndGet(),
                userId = incoming.userId,
                credentialType = incoming.credentialType,
                encodedData = incoming.encodedData,
                encryptedDisclosures = incoming.encryptedDisclosures,
                walletKey = incoming.walletKey,
                issuedAt = incoming.issuedAt,
            )
            state += persisted
            persisted
        }.`when`(repository).save(any(WalletCredential::class.java))
        `when`(repository.findByUserId("holder-1")).thenAnswer { state.filter { it.userId == "holder-1" } }
        `when`(repository.findById(any(Long::class.java))).thenAnswer { invocation ->
            val id = invocation.getArgument<Long>(0)
            Optional.ofNullable(state.firstOrNull { it.id == id })
        }
        return repository
    }

    /** Returns fixed wallet attestation transport tokens for the simulated issuance authorization steps. */
    private fun walletAttestation(): WalletAttestationTransport = WalletAttestationTransport(
        jwt = "wia.jwt",
        popJwt = "wia.pop",
        cnfJkt = "jkt",
        expiresAt = Instant.now().plusSeconds(3600),
    )

    /** Returns stub key attestation transport required by the PID issuance path in the simulated gateway. */
    private fun keyAttestation(): KeyAttestationTransport = KeyAttestationTransport(
        jwt = "ka.jwt",
        keyId = "proof-key",
        attestedJkt = "proof-jkt",
        keyStorage = "iso_18045_high",
        certification = "test-cert",
        expiresAt = Instant.now().plusSeconds(3600),
        statusListUri = "/api/v1/wallet/status-lists/PRIMARY_LIST",
        statusListIndex = 1,
    )

    private class PresentationGatewayStub : di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway {
        var lastPositiveToken: VpToken? = null

        /** Maps request URI suffixes to PID, mDL, or negative-scenario mdoc authorization requests. */
        override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution {
            val request = when {
                requestUri.endsWith("request-pid") -> requestFor("pid", "eu.europa.ec.eudi.pid.1", "given_name")
                requestUri.endsWith("request-mdl") -> requestFor("mdl", "org.iso.18013.5.1.mDL", "driving_privileges")
                else -> requestFor("neg", "org.iso.18013.5.1.mDL", "non_existing_claim")
            }
            return AuthorizationRequestResolution.Success(request)
        }

        /** Stores the last positive VP token for post-dispatch assertions in the test. */
        override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome {
            lastPositiveToken = vpToken
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }

        /** Acknowledges negative dispatches without forwarding to an external verifier. */
        override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome =
            PresentationDispatchOutcome.VerifierAccepted(null)

        /** Acknowledges error dispatches without contacting a remote verifier endpoint. */
        override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome =
            PresentationDispatchOutcome.VerifierAccepted(null)

        companion object {
            /** Builds a minimal mso_mdoc authorization request for the gateway stub companion helpers. */
            private fun requestFor(queryId: String, docType: String, claim: String): ResolvedAuthorizationRequest {
                return ResolvedAuthorizationRequest(
                    requestToken = "rt-$queryId",
                    requestUri = "http://verifier/request-$queryId",
                    clientId = "verifier-mdoc-$queryId",
                    responseMode = PresentationResponseMode.DIRECT_POST,
                    nonce = "nonce-$queryId",
                    state = "state-$queryId",
                    requirements = PresentationRequirements(
                        dcqlQueryJson = """{"credentials":[{"id":"$queryId","format":"mso_mdoc","meta":{"doctype_values":["$docType"]},"claims":[{"path":["$claim"]}]}]}""",
                        credentialQueryIds = emptyList(),
                    ),
                )
            }
        }
    }
}
