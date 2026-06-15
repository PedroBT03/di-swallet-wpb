/**
 * Tests trust snapshot loading, refresh, and availability reporting.
 */

package di.swallet.wpb.presentation.trust

import di.swallet.wpb.config.OpsProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.presentation.trust.TrustSnapshotHealthStatus
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

    /**
     * Local trust file contains an empty JSON object with no anchors configured.
     * refresh returns Unavailable whose reason mentions empty content.
     */
    @Test
    fun `empty local trust document without anchors is unavailable`() {
        val emptyJson = writeTempFile("{}")
        val props = OpenId4VpProperties().apply {
            trust.sourceMode = "file"
            trust.localVerifiersPath = emptyJson
        }
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val availability = service.refresh()
        assertTrue(availability is TrustSnapshotAvailability.Unavailable)
        assertTrue(
            (availability as TrustSnapshotAvailability.Unavailable).reason
                .contains("empty", ignoreCase = true),
        )
    }

    /**
     * Hybrid mode is selected but local paths and remote URL are all blank.
     * refresh returns Unavailable immediately.
     */
    @Test
    fun `hybrid mode with no configured sources is unavailable`() {
        val props = OpenId4VpProperties().apply {
            trust.sourceMode = "hybrid"
            trust.localVerifiersPath = ""
            trust.localTrustAnchorPemPaths = ""
            trust.remoteTrustUrl = ""
        }
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val availability = service.refresh()
        assertTrue(availability is TrustSnapshotAvailability.Unavailable)
    }

    /**
     * File mode points at local verifier JSON and anchor PEM on disk.
     * currentAvailability is Available with one anchor and the expected client_id entity.
     */
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
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val availability = service.currentAvailability()
        assertTrue(availability is TrustSnapshotAvailability.Available)
        val snapshot = (availability as TrustSnapshotAvailability.Available).snapshot
        assertEquals(1, snapshot.trustAnchors.size)
        assertTrue(snapshot.entities.containsKey("client_id:verifier-demo-client"))
    }

    /**
     * Hybrid mode loads local trust while the remote URL is unreachable.
     * Snapshot stays Available with source local and the local verifier entity present.
     */
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
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val availability = service.currentAvailability()
        assertTrue(availability is TrustSnapshotAvailability.Available)
        val snapshot = (availability as TrustSnapshotAvailability.Available).snapshot
        assertEquals("local", snapshot.source)
        assertTrue(snapshot.entities.containsKey("client_id:verifier-demo-client"))
    }

    /**
     * Local and remote documents share entity-1 but disagree on client_id and metadata source.
     * Merged snapshot keeps remote metadata and client_id bar, dropping local foo.
     */
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
            val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
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

    /**
     * Local and remote each contribute anchors, including one shared certificate.
     * Hybrid snapshot contains three deduplicated trust anchors.
     */
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
            val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
            val availability = service.currentAvailability()
            assertTrue(availability is TrustSnapshotAvailability.Available)
            val snapshot = (availability as TrustSnapshotAvailability.Available).snapshot
            assertEquals(3, snapshot.trustAnchors.size)
        } finally {
            server.stop(0)
        }
    }

    /**
     * Cached snapshot ages out and a subsequent remote refresh to a dead endpoint fails.
     * currentAvailability becomes Unavailable after the max age elapses.
     */
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
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        assertTrue(service.currentAvailability() is TrustSnapshotAvailability.Available)
        Thread.sleep(1200)
        props.trust.sourceMode = "remote"
        props.trust.remoteTrustUrl = "http://127.0.0.1:9/down"
        props.trust.remoteConnectTimeoutMs = 50
        props.trust.remoteReadTimeoutMs = 50
        val availability = service.currentAvailability()
        assertTrue(availability is TrustSnapshotAvailability.Unavailable)
    }

    /**
     * Valid cached snapshot exists when refresh switches to a failing remote source.
     * refresh still returns Available using the cached snapshot.
     */
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
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        assertTrue(service.currentAvailability() is TrustSnapshotAvailability.Available)
        props.trust.sourceMode = "remote"
        props.trust.remoteTrustUrl = "http://127.0.0.1:9/down"
        props.trust.remoteConnectTimeoutMs = 50
        props.trust.remoteReadTimeoutMs = 50
        val refreshed = service.refresh()
        assertTrue(refreshed is TrustSnapshotAvailability.Available)
    }

    /**
     * Remote-only mode is configured with no prior cache and the fetch target is down.
     * refresh returns Unavailable.
     */
    @Test
    fun `refresh failure without cache returns unavailable`() {
        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "remote"
            trust.remoteTrustUrl = "http://127.0.0.1:9/down"
            trust.remoteConnectTimeoutMs = 50
            trust.remoteReadTimeoutMs = 50
        }
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val refreshed = service.refresh()
        assertTrue(refreshed is TrustSnapshotAvailability.Unavailable)
    }

    /**
     * Production remote trust URL host is not listed in remoteAllowedHosts.
     * refresh returns Unavailable citing allow-listed hosts.
     */
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
            val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
            val refreshed = service.refresh()
            assertTrue(refreshed is TrustSnapshotAvailability.Unavailable)
            assertTrue((refreshed as TrustSnapshotAvailability.Unavailable).reason.contains("allow-listed", ignoreCase = true))
        } finally {
            server.stop(0)
        }
    }

    /**
     * Remote trust fetch fails before any snapshot is loaded.
     * health reports DOWN.
     */
    @Test
    fun `health is DOWN when no snapshot is loaded`() {
        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "remote"
            trust.remoteTrustUrl = "http://127.0.0.1:9/down"
            trust.remoteConnectTimeoutMs = 50
            trust.remoteReadTimeoutMs = 50
        }
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        val health = service.health()
        assertEquals(TrustSnapshotHealthStatus.DOWN, health.status)
    }

    /**
     * Hybrid mode loads classpath demo trust material via refresh.
     * health reports UP afterward.
     */
    @Test
    fun `health is UP after local snapshot load`() {
        val props = OpenId4VpProperties().apply {
            trust.sourceMode = "hybrid"
            trust.localVerifiersPath = "classpath:trust/demo-lote.json"
            trust.localTrustAnchorPemPaths = "classpath:trust/demo-anchor.pem"
        }
        val service = TrustSnapshotService(props, OpsProperties(), CertificateChainValidator(DefaultResourceLoader()), LoteTrustParser())
        service.refresh()
        val health = service.health()
        assertEquals(TrustSnapshotHealthStatus.UP, health.status)
    }

    /** Writes content to a temporary file and returns its absolute path for trust snapshot tests. */
    /** Writes content to a temporary file and returns its absolute path for trust property configuration. */
    private fun writeTempFile(content: String): String {
        val path = Files.createTempFile("lote-trust", ".tmp")
        Files.writeString(path, content)
        return path.toAbsolutePath().toString()
    }

    /** Starts a local HTTP server on a random port that serves the given JSON body at /trust. */
    /** Starts a local HTTP server on a random port that serves the given JSON body at /trust. */
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

    /** Formats an X509Certificate as a PEM block with standard BEGIN/END markers. */
    /** Formats an X509Certificate as a single-line-base64 PEM certificate block. */
    private fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n${Base64.getEncoder().encodeToString(cert.encoded)}\n-----END CERTIFICATE-----\n"

    /** Escapes a raw string for embedding as a JSON string literal in remote trust payloads. */
    /** Escapes newlines and quotes so a PEM string can be embedded in JSON test payloads. */
    private fun jsonString(raw: String): String =
        "\"" + raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

    /** Generates a short-lived self-signed EC trust-anchor certificate for snapshot loading tests. */
    /** Generates a self-signed EC P-256 certificate valid for one hour from the current instant. */
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
