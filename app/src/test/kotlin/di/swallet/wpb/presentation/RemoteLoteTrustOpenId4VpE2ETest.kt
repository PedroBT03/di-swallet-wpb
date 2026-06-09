package di.swallet.wpb.presentation

import com.sun.net.httpserver.HttpServer
import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.observability.InMemorySessionEventStore
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
import di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.consent.ConsentTestSupport
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.VpToken
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.matching.DefaultCredentialMatcher
import di.swallet.wpb.presentation.orchestration.DefaultPresentationFlowOrchestrator
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import di.swallet.wpb.presentation.policy.DefaultPolicyEngine
import di.swallet.wpb.presentation.registry.RegistryValidator
import di.swallet.wpb.presentation.trust.DefaultTrustValidator
import di.swallet.wpb.presentation.trust.DefaultVerifierCertificateExtractor
import di.swallet.wpb.presentation.trust.PkixAccessCertificateValidationService
import di.swallet.wpb.presentation.trust.TrustSnapshotService
import di.swallet.wpb.trust.lote.LoteTrustParser
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.core.io.DefaultResourceLoader
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date

@ConformanceTest
class RemoteLoteTrustOpenId4VpE2ETest {
    private val mdocCodec = MdocTestSupport.stack().codec

    @Test
    @ConformanceScenario("vp_remote_lote_trust")
    fun `remote TS119602 drives runtime trust decision pass and fail`() = runBlocking {
        val certChain = issueChain(clientIdDns = "verifier.example")
        val lotePayload = ts119602Payload(
            clientId = "verifier-demo-client",
            leafCert = certChain.leaf,
            rootCert = certChain.root,
        )
        val server = startServer(lotePayload)
        try {
            val trustUrl = "http://127.0.0.1:${server.address.port}/lote"

            // Real trust components
            val props = OpenId4VpProperties().apply {
                demoMode = true // demo mode allows local http trust source during tests
                trust.sourceMode = "remote"
                trust.remoteTrustUrl = trustUrl
                trust.remoteConnectTimeoutMs = 300
                trust.remoteReadTimeoutMs = 300
                trust.maxSnapshotAgeSeconds = 600
            }
            val chainValidator = CertificateChainValidator(DefaultResourceLoader())
            val trustSnapshotService = TrustSnapshotService(props, chainValidator, LoteTrustParser())
            val certExtractor = DefaultVerifierCertificateExtractor()
            val accessValidation = PkixAccessCertificateValidationService(chainValidator)
            val trustValidator = DefaultTrustValidator(props, trustSnapshotService, certExtractor, accessValidation)

            val repository = mock(WalletCredentialRepository::class.java)
            `when`(repository.findByUserId("holder-1")).thenReturn(
                listOf(
                    PresentationTestSupport.sdJwtCredential(1L, "holder-1", "given_name"),
                ),
            )

            val eventStore = InMemorySessionEventStore()
            val registryValidator = object : RegistryValidator {
                override fun validate(context: di.swallet.wpb.presentation.domain.PresentationContext) =
                    context.copy(
                        registryDecision = di.swallet.wpb.presentation.domain.RegistryDecision(
                            accepted = true,
                            reason = "not in scope for lote trust e2e test",
                            rpIdentifier = context.authorizationRequest?.clientId,
                            sourceEndpoint = "/wrp/{identifier}",
                        ),
                    )
            }
            val consentDeps = ConsentTestSupport.presentationOrchestratorDeps(
                repository = repository,
                openId4VpProperties = props,
            )
            val orchestrator = DefaultPresentationFlowOrchestrator(
                gateway = gatewayStub(clientId = "verifier-demo-client", leaf = certChain.leaf),
                repository = InMemoryPresentationSessionRepository(),
                trustValidator = trustValidator,
                registryValidator = registryValidator,
                policyEngine = DefaultPolicyEngine(props),
                credentialMatcher = PresentationTestSupport.credentialMatcher(
                    repository,
                    mdocCodec,
                    MdocDocTypeRegistry(),
                    demoMode = false,
                ),
                vpTokenBuilder = vpBuilderStub(),
                eventStore = eventStore,
                transactionLogger = TransactionLogTestSupport.noopTransactionLogger(),
                consentViewBuilder = consentDeps.consentViewBuilder,
                consentCredentialSelector = consentDeps.consentCredentialSelector,
                consentSessionGuard = consentDeps.consentSessionGuard,
                consentAuditRecorder = consentDeps.consentAuditRecorder,
                minimizationEvaluator = consentDeps.minimizationEvaluator,
            )

            // Positive: client_id + cert chain + fingerprint align with remote TS119602 document.
            val passed = orchestrator.startSession("http://verifier/req", "holder-1")
            assertEquals(PresentationState.CONSENT_PENDING, passed.state)
            assertTrue(passed.trustDecision?.trusted == true)
            assertTrue(
                eventStore.getEvents(passed.sessionMeta.sessionId)
                    .any { it.type == "trust.validation.passed" },
            )

            // Negative: same TS119602 document, but client_id mismatch -> rejected trust.
            val failingOrchestrator = DefaultPresentationFlowOrchestrator(
                gateway = gatewayStub(clientId = "untrusted-client", leaf = certChain.leaf),
                repository = InMemoryPresentationSessionRepository(),
                trustValidator = trustValidator,
                registryValidator = registryValidator,
                policyEngine = DefaultPolicyEngine(props),
                credentialMatcher = PresentationTestSupport.credentialMatcher(
                    repository,
                    mdocCodec,
                    MdocDocTypeRegistry(),
                    demoMode = false,
                ),
                vpTokenBuilder = vpBuilderStub(),
                eventStore = eventStore,
                transactionLogger = TransactionLogTestSupport.noopTransactionLogger(),
                consentViewBuilder = consentDeps.consentViewBuilder,
                consentCredentialSelector = consentDeps.consentCredentialSelector,
                consentSessionGuard = consentDeps.consentSessionGuard,
                consentAuditRecorder = consentDeps.consentAuditRecorder,
                minimizationEvaluator = consentDeps.minimizationEvaluator,
            )
            val failed = failingOrchestrator.startSession("http://verifier/req", "holder-1")
            assertEquals(PresentationState.DISPATCHED, failed.state)
            assertTrue(failed.trustDecision?.trusted == false)
            assertEquals("trust_rejected", failed.error?.code)
            assertTrue(
                eventStore.getEvents(failed.sessionMeta.sessionId)
                    .any { it.type == "trust.validation.failed" },
            )

            val availability = trustSnapshotService.currentAvailability()
            assertTrue(availability is di.swallet.wpb.trust.core.TrustSnapshotAvailability.Available)
        } finally {
            server.stop(0)
        }
    }

    private fun gatewayStub(clientId: String, leaf: X509Certificate): OpenId4VpGateway {
        val verifierInfoJson = """{"x5c":["${Base64.getEncoder().encodeToString(leaf.encoded)}"]}"""
        val resolved = ResolvedAuthorizationRequest(
            requestToken = "rt-e2e",
            requestUri = "http://verifier/req",
            clientId = clientId,
            responseMode = PresentationResponseMode.DIRECT_POST,
            nonce = "nonce-1",
            state = "state-1",
            responseUri = "http://verifier/direct_post",
            verifierDisplayName = "Verifier",
            requirements = PresentationRequirements(
                dcqlQueryJson = """{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["given_name"]}]}]}""",
                credentialQueryIds = listOf("pid"),
                requestedFormats = setOf(CredentialFormat.SD_JWT),
            ),
            verifierInfoJson = verifierInfoJson,
        )
        return object : OpenId4VpGateway {
            override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution =
                AuthorizationRequestResolution.Success(resolved)

            override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome =
                PresentationDispatchOutcome.VerifierAccepted(null)

            override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome =
                PresentationDispatchOutcome.VerifierAccepted(null)

            override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome =
                PresentationDispatchOutcome.VerifierAccepted(null)
        }
    }

    private fun vpBuilderStub(): VpTokenBuilder = object : VpTokenBuilder {
        override fun build(context: di.swallet.wpb.presentation.domain.PresentationContext) =
            context.copy(
                vpToken = VpToken(
                    presentationsByQueryId = mapOf("pid" to listOf("VP-STUB")),
                    format = CredentialFormat.SD_JWT,
                ),
            )
    }

    private data class CertChain(val root: X509Certificate, val leaf: X509Certificate)

    private fun issueChain(clientIdDns: String): CertChain {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val rootKeys = generator.generateKeyPair()
        val leafKeys = generator.generateKeyPair()
        val root = selfSignedCa(rootKeys, "CN=LoTE Root CA")
        val leaf = issuedLeaf(root, rootKeys, leafKeys, "CN=Verifier Leaf", clientIdDns)
        return CertChain(root = root, leaf = leaf)
    }

    private fun selfSignedCa(keys: KeyPair, subjectDn: String): X509Certificate {
        val subject = X500Name(subjectDn)
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.nanoTime()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(86400)),
            subject,
            keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun issuedLeaf(
        issuerCert: X509Certificate,
        issuerKeys: KeyPair,
        leafKeys: KeyPair,
        subjectDn: String,
        dnsSan: String,
    ): X509Certificate {
        val issuer = X500Name(issuerCert.subjectX500Principal.name)
        val subject = X500Name(subjectDn)
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.nanoTime() + 1),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            subject,
            leafKeys.public,
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, dnsSan)),
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun ts119602Payload(clientId: String, leafCert: X509Certificate, rootCert: X509Certificate): String {
        val leafB64 = Base64.getEncoder().encodeToString(leafCert.encoded)
        val rootPem = pem(rootCert)
        return """
            {
              "ListAndSchemeInformation": {
                "LoTESequenceNumber": 7,
                "ListIssueDateTime": "2026-06-01T00:00:00Z",
                "NextUpdate": {"dateTime": "2026-07-01T00:00:00Z"},
                "LoTEType": "http://uri.etsi.org/19602/LoTEType/EU/AccessCA",
                "StatusDeterminationApproach": "http://uri.etsi.org/19602/StatusDetn/EU"
              },
              "TrustedEntitiesList": [
                {
                  "TrustedEntityInformation": {
                    "TEIdentifier": "entity-rp-1"
                  },
                  "clientId": "$clientId",
                  "TrustedEntityServices": [
                    {
                      "ServiceInformation": {
                        "ServiceTypeIdentifier": "urn:service:access",
                        "ServiceStatus": "http://uri.etsi.org/19602/Status/granted",
                        "ServiceIdentifier": "svc-access-1",
                        "ServiceDigitalIdentity": {
                          "X509Certificates": [
                            {"encoding":"base64","val":"$leafB64"}
                          ]
                        }
                      }
                    }
                  ]
                }
              ],
              "trustAnchorsPem": [${jsonString(rootPem)}]
            }
        """.trimIndent()
    }

    private fun startServer(body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/lote") { exchange ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n${Base64.getEncoder().encodeToString(cert.encoded)}\n-----END CERTIFICATE-----\n"

    private fun jsonString(raw: String): String =
        "\"" + raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
}
