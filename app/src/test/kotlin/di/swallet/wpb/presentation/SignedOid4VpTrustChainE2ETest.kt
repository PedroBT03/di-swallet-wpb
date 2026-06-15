/**
 * End-to-end tests for signed oid4 vp trust chain.
 */

package di.swallet.wpb.presentation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.observability.InMemorySessionEventStore
import di.swallet.wpb.transactionlog.TransactionLogTestSupport
import di.swallet.wpb.openid4vp.adapter.SdkOpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.consent.ConsentTestSupport
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
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
import di.swallet.wpb.presentation.trust.TrustTestCertificates
import di.swallet.wpb.trust.lote.LoteTrustParser
import eu.europa.ec.eudi.openid4vp.CoseAlgorithm
import eu.europa.ec.eudi.openid4vp.ErrorDispatchPolicy
import eu.europa.ec.eudi.openid4vp.OpenId4VPConfig
import eu.europa.ec.eudi.openid4vp.OpenId4Vp
import eu.europa.ec.eudi.openid4vp.PreregisteredClient
import eu.europa.ec.eudi.openid4vp.ResponseEncryptionConfiguration
import eu.europa.ec.eudi.openid4vp.SupportedClientIdPrefix
import eu.europa.ec.eudi.openid4vp.VPConfiguration
import eu.europa.ec.eudi.openid4vp.VpFormatsSupported
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.core.io.DefaultResourceLoader
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm

/**
 * End-to-end trust path for signed OID4VP authorization requests:
 * signed JWT request_uri → SdkOpenId4VpGateway (demo fallback) →
 * DefaultVerifierCertificateExtractor → PkixAccessCertificateValidationService →
 * DefaultTrustValidator.
 */
@ConformanceTest
class SignedOid4VpTrustChainE2ETest {
    private val mdocCodec = MdocTestSupport.stack().codec

    /**
     * Signed authorization request JWT embeds x5c and local TS119602 trust anchors are loaded.
     * Orchestrator reaches consent with trusted decision, trust.passed event, and PKIX Trusted validation.
     */
    @Test
    @ConformanceScenario("vp_pkix_access_certificate_trust")
    fun `signed authorization request drives extractor to pkix trust validation`() = runBlocking {
        val certChain = TrustTestCertificates.issueChain()
        val lotePath = writeTempFile(ts119602Payload("verifier-demo-client", certChain.leaf, certChain.root))
        val anchorPath = writeTempFile(TrustTestCertificates.pem(certChain.root))

        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "file"
            trust.localVerifiersPath = lotePath
            trust.localTrustAnchorPemPaths = anchorPath
            trust.allowFailOpenInDemoMode = false
            trust.maxSnapshotAgeSeconds = 600
        }

        val chainValidator = CertificateChainValidator(DefaultResourceLoader())
        val trustSnapshotService = TrustSnapshotService(props, di.swallet.wpb.config.OpsProperties(), chainValidator, LoteTrustParser())
        val trustValidator = DefaultTrustValidator(
            properties = props,
            trustSnapshotResolver = trustSnapshotService,
            certificateExtractor = DefaultVerifierCertificateExtractor(),
            certificateValidationService = PkixAccessCertificateValidationService(chainValidator),
        )

        val requestSigner = ecKeyPair()
        val leafB64 = Base64.getEncoder().encodeToString(certChain.leaf.encoded)
        val signedJwt = signAuthorizationRequestJwt(
            signer = requestSigner,
            clientId = "verifier-demo-client",
            verifierInfoJson = """{"x5c":["$leafB64"]}""",
        )
        val server = startRequestServer(signedJwt)
        try {
            val requestUri = "http://127.0.0.1:${server.address.port}/request"
            val gateway = sdkGateway(demoMode = true)
            val repository = mock(WalletCredentialRepository::class.java)
            `when`(repository.findByUserId("holder-1")).thenReturn(
                listOf(PresentationTestSupport.sdJwtCredential(1L, "holder-1", "given_name")),
            )
            val eventStore = InMemorySessionEventStore()
            val registryValidator = object : RegistryValidator {
                /** Accepts registry validation so the test isolates signed-request trust extraction and PKIX. */
                override fun validate(context: di.swallet.wpb.presentation.domain.PresentationContext) =
                    context.copy(
                        registryDecision = di.swallet.wpb.presentation.domain.RegistryDecision(
                            accepted = true,
                            reason = "not in scope",
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
                gateway = gateway,
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
                wpbMetrics = WpbMetricsTestSupport.noop(),
            wscaSciGrantService = di.swallet.wpb.security.WscaSciTestSupport.grantService(),
            )

            val passed = orchestrator.startSession(requestUri, "holder-1")
            assertEquals(PresentationState.CONSENT_PENDING, passed.state)
            assertTrue(passed.trustDecision?.trusted == true)
            assertTrue(
                eventStore.getEvents(passed.sessionMeta.sessionId)
                    .any { it.type == "trust.validation.passed" },
            )

            val resolution = gateway.resolveRequestUri(requestUri)
            assertTrue(resolution is AuthorizationRequestResolution.Success)
            val resolved = (resolution as AuthorizationRequestResolution.Success).request
            val extracted = DefaultVerifierCertificateExtractor().extract(resolved)
            assertTrue(extracted != null)
            val snapshot = (trustSnapshotService.currentAvailability()
                as di.swallet.wpb.trust.core.TrustSnapshotAvailability.Available).snapshot
            val pkix = PkixAccessCertificateValidationService(chainValidator)
                .validate("verifier-demo-client", extracted!!, snapshot)
            assertTrue(pkix is di.swallet.wpb.presentation.trust.AccessCertificateValidationResult.Trusted)
        } finally {
            server.stop(0)
        }
    }

    /** Creates SdkOpenId4VpGateway with HAIP VP formats and demo-mode resolution fallback enabled. */
    private fun sdkGateway(demoMode: Boolean): SdkOpenId4VpGateway {
        val config = OpenId4VPConfig(
            supportedClientIdPrefixes = listOf(
                SupportedClientIdPrefix.Preregistered(
                    PreregisteredClient("verifier-demo-client", "Demo Verifier"),
                ),
                SupportedClientIdPrefix.RedirectUri,
            ),
            responseEncryptionConfiguration = ResponseEncryptionConfiguration.Supported(
                supportedAlgorithms = listOf(JWEAlgorithm.ECDH_ES),
                supportedMethods = listOf(EncryptionMethod.A256GCM),
            ),
            vpConfiguration = VPConfiguration(
                vpFormatsSupported = VpFormatsSupported(
                    VpFormatsSupported.SdJwtVc.HAIP,
                    VpFormatsSupported.MsoMdoc(
                        issuerAuthAlgorithms = listOf(CoseAlgorithm(-7)),
                        deviceAuthAlgorithms = listOf(CoseAlgorithm(-7)),
                    ),
                ),
            ),
            errorDispatchPolicy = ErrorDispatchPolicy.AllClients,
        )
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            }
            expectSuccess = true
        }
        return SdkOpenId4VpGateway(OpenId4Vp(config, httpClient), demoMode = demoMode)
    }

    /** Signs an ES256 authorization request JWT embedding client_id, DCQL, and verifier_info claims. */
    private fun signAuthorizationRequestJwt(
        signer: java.security.KeyPair,
        clientId: String,
        verifierInfoJson: String,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .claim("client_id", clientId)
            .claim("response_mode", "direct_post")
            .claim("response_type", "vp_token")
            .claim("response_uri", "http://verifier.example/direct_post")
            .claim("nonce", "nonce-signed-e2e")
            .claim("state", "state-signed-e2e")
            .claim(
                "dcql",
                mapOf(
                    "credentials" to listOf(
                        mapOf(
                            "id" to "pid",
                            "format" to "vc+sd-jwt",
                            "claims" to listOf(mapOf("path" to listOf("given_name"))),
                        ),
                    ),
                ),
            )
            .claim("verifier_info", jacksonObjectMapper().readValue(verifierInfoJson, Map::class.java))
            .issueTime(Date())
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType.JWT)
            .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(signer.private as ECPrivateKey))
        return jwt.serialize()
    }

    /** Generates an EC P-256 key pair for signing authorization request JWTs in the test. */
    private fun ecKeyPair(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    /** Starts a local HTTP server that serves the signed authorization request JWT at /request. */
    private fun startRequestServer(jwt: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/request") { exchange ->
            val bytes = jwt.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/oauth-authz-req+jwt")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    /** Returns a VpTokenBuilder that attaches a stub SD-JWT VP for consent-dispatch assertions. */
    private fun vpBuilderStub(): VpTokenBuilder = object : VpTokenBuilder {
        /** Attaches a stub SD-JWT VP token with one pid presentation for consent-dispatch tests. */
        override fun build(context: di.swallet.wpb.presentation.domain.PresentationContext) =
            context.copy(
                vpToken = VpToken(
                    presentationsByQueryId = mapOf("pid" to listOf("VP-STUB")),
                    format = di.swallet.wpb.presentation.domain.CredentialFormat.SD_JWT,
                ),
            )
    }

    /** Builds a TS119602 LoTE JSON payload with the leaf certificate embedded in ServiceDigitalIdentity. */
    private fun ts119602Payload(clientId: String, leafCert: X509Certificate, rootCert: X509Certificate): String {
        val leafB64 = Base64.getEncoder().encodeToString(leafCert.encoded)
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
                  "TrustedEntityInformation": { "TEIdentifier": "entity-signed-e2e" },
                  "clientId": "$clientId",
                  "TrustedEntityServices": [
                    {
                      "ServiceInformation": {
                        "ServiceTypeIdentifier": "urn:service:access",
                        "ServiceStatus": "http://uri.etsi.org/19602/Status/granted",
                        "ServiceIdentifier": "svc-access-signed",
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
              "trustAnchorsPem": [${TrustTestCertificates.jsonString(TrustTestCertificates.pem(rootCert))}]
            }
        """.trimIndent()
    }

    /** Writes content to a temporary file and returns its absolute path for trust property paths. */
    private fun writeTempFile(content: String): String {
        val path = Files.createTempFile("signed-trust-e2e-", ".tmp")
        Files.writeString(path, content)
        return path.toAbsolutePath().toString()
    }
}
