package di.swallet.wpb.presentation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.VpToken
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.matching.DefaultCredentialMatcher
import di.swallet.wpb.presentation.orchestration.DefaultPresentationFlowOrchestrator
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import di.swallet.wpb.presentation.policy.DefaultPolicyEngine
import di.swallet.wpb.presentation.registry.DefaultRegistryValidator
import di.swallet.wpb.presentation.registry.RpRegistryResolver
import di.swallet.wpb.presentation.registry.RpRegistrySignatureVerifier
import di.swallet.wpb.presentation.registry.Ts5RpRegistryHttpClient
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
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class Ts5RegistryOpenId4VpE2ETest {
    private val mdocCodec = MdocTestSupport.stack().codec

    @Test
    fun `ts5 signed registry response changes openid4vp runtime decision`() = runBlocking {
        val trustChain = issueChain(clientIdDns = "verifier.example")
        val registrySigner = ecKeyPair()
        val registryKeyPath = writePublicKeyPem(registrySigner)
        val checkIntendedUsePositive = AtomicBoolean(true)
        val server = startServer(
            lotePayload = ts119602Payload(
                clientId = "rp-123",
                leafCert = trustChain.leaf,
                rootCert = trustChain.root,
            ),
            registryRecordJwtSupplier = {
                signRegistryJwt(
                    signer = registrySigner,
                    data = walletRelyingPartyRecordData(identifier = "rp-123"),
                    audience = "wpb-wallet",
                )
            },
            registryCheckJwtSupplier = {
                signRegistryJwt(
                    signer = registrySigner,
                    data = mapOf(
                        "isRegistered" to checkIntendedUsePositive.get(),
                        "details" to if (checkIntendedUsePositive.get()) "registered" else "not registered",
                    ),
                    audience = "wpb-wallet",
                )
            },
        )
        try {
            val props = OpenId4VpProperties().apply {
                demoMode = true
                trust.sourceMode = "remote"
                trust.remoteTrustUrl = "http://127.0.0.1:${server.address.port}/lote"
                trust.remoteConnectTimeoutMs = 300
                trust.remoteReadTimeoutMs = 300
                trust.maxSnapshotAgeSeconds = 600
                registry.enabled = true
                registry.specificationVersion = "TS5-1.2"
                registry.baseUrl = "http://127.0.0.1:${server.address.port}/registry"
                registry.connectTimeoutMs = 300
                registry.readTimeoutMs = 300
                registry.maxCacheAgeSeconds = 600
                registry.verificationKeyPemPaths = registryKeyPath
                registry.allowedIssuers = "https://registry.example"
                registry.expectedAudience = "wpb-wallet"
                registry.requireSignedResponses = true
                registry.requireSignedEnvelopeFields = true
                registry.preferCheckIntendedUseEndpoint = true
            }

            val chainValidator = CertificateChainValidator(DefaultResourceLoader())
            val trustSnapshotService = TrustSnapshotService(props, chainValidator, LoteTrustParser())
            val trustValidator = DefaultTrustValidator(
                properties = props,
                trustSnapshotResolver = trustSnapshotService,
                certificateExtractor = DefaultVerifierCertificateExtractor(),
                certificateValidationService = PkixAccessCertificateValidationService(chainValidator),
            )
            val registryValidator = DefaultRegistryValidator(
                properties = props,
                resolver = RpRegistryResolver(
                    properties = props,
                    client = Ts5RpRegistryHttpClient(props),
                    signatureVerifier = RpRegistrySignatureVerifier(props),
                ),
            )

            val repository = mock(WalletCredentialRepository::class.java)
            `when`(repository.findByUserId("holder-1")).thenReturn(
                listOf(PresentationTestSupport.sdJwtCredential(1L, "holder-1", "given_name")),
            )
            val eventStore = InMemorySessionEventStore()

            val orchestrator = DefaultPresentationFlowOrchestrator(
                gateway = gatewayStub(clientId = "rp-123", leaf = trustChain.leaf),
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
            )

            // Positive: trust is valid and TS5 check-intended-use is true.
            checkIntendedUsePositive.set(true)
            val passed = orchestrator.startSession("http://verifier/req", "holder-1")
            assertEquals(PresentationState.CONSENT_PENDING, passed.state)
            assertTrue(passed.trustDecision?.trusted == true)
            assertTrue(passed.registryDecision?.accepted == true)
            assertTrue(
                eventStore.getEvents(passed.sessionMeta.sessionId).any { it.type == "registry.validation.passed" },
            )

            // Negative: same trust material and same verifier cert, only registry check flips to false.
            checkIntendedUsePositive.set(false)
            val rejected = orchestrator.startSession("http://verifier/req", "holder-1")
            assertEquals(PresentationState.DISPATCHED, rejected.state)
            assertTrue(rejected.trustDecision?.trusted == true)
            assertTrue(rejected.registryDecision?.accepted == false)
            assertEquals("registry_rejected", rejected.error?.code)
            assertTrue(
                eventStore.getEvents(rejected.sessionMeta.sessionId).any { it.type == "registry.validation.failed" },
            )
        } finally {
            server.stop(0)
        }
    }

    private fun gatewayStub(clientId: String, leaf: X509Certificate): OpenId4VpGateway {
        val verifierInfoJson = """{"x5c":["${Base64.getEncoder().encodeToString(leaf.encoded)}"]}"""
        val resolved = ResolvedAuthorizationRequest(
            requestToken = "rt-ts5-registry-e2e",
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
                credentialQueries = listOf(
                    CredentialQuery(
                        id = "pid",
                        format = CredentialFormat.SD_JWT,
                        requestedClaimPaths = listOf(di.swallet.wpb.presentation.domain.ClaimPath.key("given_name")),
                    ),
                ),
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
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
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

    private fun startServer(
        lotePayload: String,
        registryRecordJwtSupplier: () -> String,
        registryCheckJwtSupplier: () -> String,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/lote") { exchange ->
            writeResponse(exchange, 200, "application/json", lotePayload)
        }
        server.createContext("/registry/wrp/rp-123") { exchange ->
            writeResponse(exchange, 200, "application/jwt", registryRecordJwtSupplier())
        }
        server.createContext("/registry/wrp/check-intended-use") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            if (!query.contains("rpidentifier=rp-123")) {
                writeResponse(exchange, 400, "text/plain", "missing rpidentifier")
            } else {
                writeResponse(exchange, 200, "application/jwt", registryCheckJwtSupplier())
            }
        }
        server.start()
        return server
    }

    private fun writeResponse(exchange: HttpExchange, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun ts119602Payload(clientId: String, leafCert: X509Certificate, rootCert: X509Certificate): String {
        val leafB64 = Base64.getEncoder().encodeToString(leafCert.encoded)
        val rootPem = pem(rootCert)
        return """
            {
              "ListAndSchemeInformation": {
                "LoTESequenceNumber": 8,
                "ListIssueDateTime": "2026-06-01T00:00:00Z",
                "NextUpdate": {"dateTime": "2026-07-01T00:00:00Z"},
                "LoTEType": "http://uri.etsi.org/19602/LoTEType/EU/AccessCA",
                "StatusDeterminationApproach": "http://uri.etsi.org/19602/StatusDetn/EU"
              },
              "TrustedEntitiesList": [
                {
                  "TrustedEntityInformation": {
                    "TEIdentifier": "entity-rp-123"
                  },
                  "clientId": "$clientId",
                  "TrustedEntityServices": [
                    {
                      "ServiceInformation": {
                        "ServiceTypeIdentifier": "urn:service:access",
                        "ServiceStatus": "http://uri.etsi.org/19602/Status/granted",
                        "ServiceIdentifier": "svc-access-rp-123",
                        "ServiceDigitalIdentity": {
                          "X509Certificates": [{"encoding":"base64","val":"$leafB64"}]
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

    private fun walletRelyingPartyRecordData(identifier: String): Map<String, Any> = mapOf(
        "identifier" to listOf(mapOf("identifier" to identifier, "type" to "http://data.europa.eu/eudi/id/EUID")),
        "tradeName" to "RP Example",
        "registryURI" to "https://registry.example",
        "supportURI" to listOf("https://rp.example/support"),
        "entitlements" to listOf("https://uri.etsi.org/19475/Entitlement/Service_Provider"),
        "supervisoryAuthority" to mapOf(
            "name" to "DPA PT",
            "country" to "PT",
            "email" to listOf("dpa@example.org"),
        ),
        "intendedUse" to listOf(
            mapOf(
                "intendedUseIdentifier" to "iu-kyc",
                "purpose" to listOf(mapOf("lang" to "en", "content" to "KYC onboarding")),
                "privacyPolicy" to listOf(mapOf("policyURI" to "https://rp.example/privacy", "type" to "Privacy Statement")),
                "credential" to listOf(
                    mapOf(
                        "format" to "dc+sd-jwt",
                        "meta" to "pid",
                        "claim" to listOf(mapOf("path" to "given_name")),
                    ),
                ),
            ),
        ),
    )

    private fun signRegistryJwt(
        signer: KeyPair,
        data: Any,
        audience: String,
    ): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer("https://registry.example")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(180)))
            .notBeforeTime(Date.from(now.minusSeconds(5)))
            .audience(audience)
            .jwtID("reg-${System.nanoTime()}")
            .claim("data", data)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType.JWT)
            .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(signer.private as ECPrivateKey))
        return jwt.serialize()
    }

    private fun ecKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun writePublicKeyPem(keyPair: KeyPair): String {
        val encoded = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n"
        val path = Files.createTempFile("ts5-registry-key", ".pem")
        Files.writeString(path, pem)
        return "file:${path.toAbsolutePath()}"
    }

    private fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n${Base64.getEncoder().encodeToString(cert.encoded)}\n-----END CERTIFICATE-----\n"

    private fun jsonString(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
