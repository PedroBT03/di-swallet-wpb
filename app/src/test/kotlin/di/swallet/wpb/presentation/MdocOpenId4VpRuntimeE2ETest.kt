package di.swallet.wpb.presentation

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.issuance.storage.JpaIssuedCredentialStorage
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
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

    @Test
    @ConformanceScenario("vp_mdoc_runtime_e2e")
    fun `mdoc runtime supports PID and mDL positive and negative`() = runBlocking {
        val storedCredentials = mutableListOf<WalletCredential>()
        val credentialRepository = inMemoryRepository(storedCredentials)
        val storage = JpaIssuedCredentialStorage(
            repository = credentialRepository,
            walletKeyRepository = stack.walletKeyRepository,
            disclosureCipher = DisclosureCipherService(WalletProperties()),
            mdocCredentialCodec = mdocCodec,
            mdocDocTypeRegistry = mdocRegistry,
            keyBindingRuntimeService = mock(KeyBindingRuntimeService::class.java),
            credentialStatusParser = di.swallet.wpb.revocation.RevocationTestSupport.credentialStatusParser(),
        )

        val issuanceGateway = SimulatedOpenId4VciGateway(
            properties = OpenId4VciProperties(),
            mdocDocTypeRegistry = mdocRegistry,
            mdocCredentialCodec = mdocCodec,
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
            disclosureCipherService = DisclosureCipherService(WalletProperties()),
            disclosureSelector = PresentationTestSupport.disclosureSelector,
            keyBindingJwtSigner = object : KeyBindingJwtSigner {
                override fun signKeyBindingJwt(userId: String, payload: Map<String, Any>): String = "kb.jwt.stub"
            },
        )
        val vpBuilder = DefaultVpTokenBuilder(
            sdJwtVpBuilder = sdJwtBuilder,
            mdocVpBuilder = MdocVpBuilder(repository, mdocCodec, mdocRegistry),
        )
        val consentDeps = ConsentTestSupport.presentationOrchestratorDeps(repository = repository)
        return DefaultPresentationFlowOrchestrator(
            gateway = gateway,
            repository = InMemoryPresentationSessionRepository(),
            trustValidator = object : TrustValidator {
                override fun validate(context: PresentationContext): PresentationContext =
                    context.copy(trustDecision = TrustDecision(trusted = true, reason = "mdoc-runtime-e2e"))
            },
            registryValidator = object : RegistryValidator {
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
        )
    }

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

    private fun walletAttestation(): WalletAttestationTransport = WalletAttestationTransport(
        jwt = "wia.jwt",
        popJwt = "wia.pop",
        cnfJkt = "jkt",
        expiresAt = Instant.now().plusSeconds(3600),
    )

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

        override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution {
            val request = when {
                requestUri.endsWith("request-pid") -> requestFor("pid", "eu.europa.ec.eudi.pid.1", "given_name")
                requestUri.endsWith("request-mdl") -> requestFor("mdl", "org.iso.18013.5.1.mDL", "driving_privileges")
                else -> requestFor("neg", "org.iso.18013.5.1.mDL", "non_existing_claim")
            }
            return AuthorizationRequestResolution.Success(request)
        }

        override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome {
            lastPositiveToken = vpToken
            return PresentationDispatchOutcome.VerifierAccepted(null)
        }

        override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome =
            PresentationDispatchOutcome.VerifierAccepted(null)

        override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome =
            PresentationDispatchOutcome.VerifierAccepted(null)

        companion object {
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
