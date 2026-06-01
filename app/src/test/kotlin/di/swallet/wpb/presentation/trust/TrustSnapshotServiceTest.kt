package di.swallet.wpb.presentation.trust

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import di.swallet.wpb.trust.lote.LoteTrustParser
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.net.InetSocketAddress
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import com.sun.net.httpserver.HttpServer

class TrustSnapshotServiceTest {

    @Test
    fun `loads trust snapshot from local files in file mode`() {
        val cert = selfSignedCert()
        val anchorPem = writeTempFile(
            """
            -----BEGIN CERTIFICATE-----
            ${Base64.getEncoder().encodeToString(cert.encoded)}
            -----END CERTIFICATE-----
            """.trimIndent() + "\n",
        )
        val verifierJson = writeTempFile(
            """
            {
              "verifiers": [
                { "clientId": "verifier-demo-client", "certSha256": ["AABBCC"] }
              ]
            }
            """.trimIndent(),
        )
        val props = OpenId4VpProperties().apply {
            trust.sourceMode = "file"
            trust.localVerifiersPath = verifierJson
            trust.localTrustAnchorPemPaths = anchorPem
        }
        val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val availability = service.currentAvailability()
        assertTrue(availability is TrustSnapshotAvailability.Available)
        val snapshot = (availability as TrustSnapshotAvailability.Available).snapshot
        assertEquals(1, snapshot.trustAnchors.size)
        assertTrue(snapshot.entities.containsKey("client_id:verifier-demo-client"))
    }

    @Test
    fun `hybrid mode tolerates remote outage and keeps local snapshot`() {
        val cert = selfSignedCert()
        val anchorPem = writeTempFile(
            """
            -----BEGIN CERTIFICATE-----
            ${Base64.getEncoder().encodeToString(cert.encoded)}
            -----END CERTIFICATE-----
            """.trimIndent() + "\n",
        )
        val verifierJson = writeTempFile(
            """
            {
              "verifiers": [
                { "clientId": "verifier-demo-client", "certSha256": ["AABBCC"] }
              ]
            }
            """.trimIndent(),
        )
        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "hybrid"
            trust.localVerifiersPath = verifierJson
            trust.localTrustAnchorPemPaths = anchorPem
            trust.remoteTrustUrl = "http://127.0.0.1:9/unavailable"
            trust.remoteConnectTimeoutMs = 50
            trust.remoteReadTimeoutMs = 50
        }
        val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val availability = service.currentAvailability()
        assertTrue(availability is TrustSnapshotAvailability.Available)
        val snapshot = (availability as TrustSnapshotAvailability.Available).snapshot
        assertEquals("local", snapshot.source)
        assertTrue(snapshot.entities.containsKey("client_id:verifier-demo-client"))
    }

    @Test
    fun `hybrid mode uses remote over local for same entityId`() {
        val localCert = selfSignedCert()
        val remoteCert = selfSignedCert()
        val localAnchorPem = pem(localCert)
        val remoteAnchorPem = pem(remoteCert)
        val localVerifiers = writeTempFile(
            """
            {
              "verifiers": [
                { "entityId": "entity-1", "clientId": "foo", "metadata": {"source":"local"} }
              ]
            }
            """.trimIndent(),
        )
        val server = startServer(
            """
            {
              "verifiers": [
                { "entityId": "entity-1", "clientId": "bar", "metadata": {"source":"remote"} }
              ],
              "trustAnchorsPem": [
                ${jsonString(remoteAnchorPem)}
              ]
            }
            """.trimIndent(),
        )
        try {
            val props = OpenId4VpProperties().apply {
                demoMode = true
                trust.sourceMode = "hybrid"
                trust.localVerifiersPath = localVerifiers
                trust.localTrustAnchorPemPaths = writeTempFile(localAnchorPem)
                trust.remoteTrustUrl = "http://127.0.0.1:${server.address.port}/trust"
            }
            val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
            val availability = service.currentAvailability()
            assertTrue(availability is TrustSnapshotAvailability.Available)
            val snapshot = (availability as TrustSnapshotAvailability.Available).snapshot
            val entity = snapshot.entities["entity-1"]
            assertEquals("remote", entity?.metadata?.get("source"))
            val clientIds = entity?.bindings
                ?.filter { it.key == "client_id" }
                ?.map { it.value }
                ?.toSet()
                .orEmpty()
            assertEquals(setOf("bar"), clientIds)
            assertFalse("foo" in clientIds)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `hybrid mode deduplicates anchors by fingerprint`() {
        val shared = selfSignedCert()
        val localOnly = selfSignedCert()
        val remoteOnly = selfSignedCert()
        val localAnchors = writeTempFile(pem(shared) + pem(localOnly))
        val localVerifiers = writeTempFile("""{"verifiers":[{"clientId":"verifier-demo-client"}]}""")
        val server = startServer(
            """
            {
              "verifiers": [{"clientId":"verifier-demo-client"}],
              "trustAnchorsPem": [
                ${jsonString(pem(shared))},
                ${jsonString(pem(remoteOnly))}
              ]
            }
            """.trimIndent(),
        )
        try {
            val props = OpenId4VpProperties().apply {
                demoMode = true
                trust.sourceMode = "hybrid"
                trust.localVerifiersPath = localVerifiers
                trust.localTrustAnchorPemPaths = localAnchors
                trust.remoteTrustUrl = "http://127.0.0.1:${server.address.port}/trust"
            }
            val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
            val availability = service.currentAvailability()
            assertTrue(availability is TrustSnapshotAvailability.Available)
            val snapshot = (availability as TrustSnapshotAvailability.Available).snapshot
            assertEquals(3, snapshot.trustAnchors.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `snapshot expired and refresh fails returns unavailable`() {
        val cert = selfSignedCert()
        val anchorPem = writeTempFile(pem(cert))
        val localVerifiers = writeTempFile("""{"verifiers":[{"clientId":"verifier-demo-client"}]}""")
        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "file"
            trust.localVerifiersPath = localVerifiers
            trust.localTrustAnchorPemPaths = anchorPem
            trust.maxSnapshotAgeSeconds = 1
        }
        val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        assertTrue(service.currentAvailability() is TrustSnapshotAvailability.Available)
        Thread.sleep(1200)
        props.trust.sourceMode = "remote"
        props.trust.remoteTrustUrl = "http://127.0.0.1:9/down"
        props.trust.remoteConnectTimeoutMs = 50
        props.trust.remoteReadTimeoutMs = 50
        val availability = service.currentAvailability()
        assertTrue(availability is TrustSnapshotAvailability.Unavailable)
    }

    @Test
    fun `refresh failure keeps valid cached snapshot`() {
        val cert = selfSignedCert()
        val anchorPem = writeTempFile(pem(cert))
        val localVerifiers = writeTempFile("""{"verifiers":[{"clientId":"verifier-demo-client"}]}""")
        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "file"
            trust.localVerifiersPath = localVerifiers
            trust.localTrustAnchorPemPaths = anchorPem
            trust.maxSnapshotAgeSeconds = 600
        }
        val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        assertTrue(service.currentAvailability() is TrustSnapshotAvailability.Available)
        props.trust.sourceMode = "remote"
        props.trust.remoteTrustUrl = "http://127.0.0.1:9/down"
        props.trust.remoteConnectTimeoutMs = 50
        props.trust.remoteReadTimeoutMs = 50
        val refreshed = service.refresh()
        assertTrue(refreshed is TrustSnapshotAvailability.Available)
    }

    @Test
    fun `refresh failure without cache returns unavailable`() {
        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "remote"
            trust.remoteTrustUrl = "http://127.0.0.1:9/down"
            trust.remoteConnectTimeoutMs = 50
            trust.remoteReadTimeoutMs = 50
        }
        val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val refreshed = service.refresh()
        assertTrue(refreshed is TrustSnapshotAvailability.Unavailable)
    }

    @Test
    fun `production rejects remote payload from host not in allow-list`() {
        val server = startServer("""{"verifiers":[{"clientId":"verifier-demo-client"}]}""")
        try {
            val props = OpenId4VpProperties().apply {
                demoMode = false
                trust.sourceMode = "remote"
                trust.remoteTrustUrl = "https://127.0.0.1:${server.address.port}/trust"
                trust.remoteAllowedHosts = "trusted.example"
                trust.remoteConnectTimeoutMs = 100
                trust.remoteReadTimeoutMs = 100
            }
            val service = TrustSnapshotService(props, CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
            val refreshed = service.refresh()
            assertTrue(refreshed is TrustSnapshotAvailability.Unavailable)
            assertTrue((refreshed as TrustSnapshotAvailability.Unavailable).reason.contains("allow-listed", ignoreCase = true))
        } finally {
            server.stop(0)
        }
    }

    private fun writeTempFile(content: String): String {
        val path = Files.createTempFile("phase5-trust", ".tmp")
        Files.writeString(path, content)
        return path.toAbsolutePath().toString()
    }

    private fun startServer(body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/trust") { exchange ->
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

    private fun selfSignedCert(): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val subject = X500Name("CN=TrustAnchor")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.nanoTime()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
