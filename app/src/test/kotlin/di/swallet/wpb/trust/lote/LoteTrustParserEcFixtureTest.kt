/**
 * Tests lote trust parser ec fixture.
 */

package di.swallet.wpb.trust.lote

import di.swallet.wpb.presentation.trust.TrustTestCertificates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LoteTrustParserEcFixtureTest {
    private val parser = LoteTrustParser()

    /**
     * Loads the EC TS119602 AccessCA fixture with an injected leaf certificate and root trust anchor.
     * Parsed entity must expose client ID bindings, cert SHA256, SAN DNS, granted status, and a snapshot with one trust anchor.
     */
    @Test
    fun `parses EC TS119602 AccessCA fixture with service digital identity bindings`() {
        val chain = TrustTestCertificates.issueChain()
        val template = javaClass.classLoader
            .getResourceAsStream("trust/ec-ts119602-access-ca-fixture.json")!!
            .bufferedReader()
            .readText()
        val payload = template
            .replace("__LEAF_CERT_BASE64__", java.util.Base64.getEncoder().encodeToString(chain.leaf.encoded))
            .replace("\"__ROOT_CERT_PEM__\"", TrustTestCertificates.jsonString(TrustTestCertificates.pem(chain.root)))

        val document = parser.parseDocument(payload)
        assertEquals("42", document.sequenceNumber)
        assertEquals("http://uri.etsi.org/19602/LoTEType/EU/AccessCA", document.listType)
        assertEquals(1, document.entities.size)

        val entity = document.entities.single()
        assertEquals("te-ec-access-ca-001", entity.entityId)
        assertTrue(entity.clientIds.contains("verifier-demo-client"))
        assertTrue(entity.certSha256.contains(TrustTestCertificates.sha256Hex(chain.leaf)))
        assertTrue(entity.sanDns.contains("verifier.example"))
        assertEquals("http://uri.etsi.org/19602/Status/granted", entity.metadata["etsi.serviceStatus"])

        val snapshot = parser.toTrustSnapshot(
            document = document,
            source = LoteTrustSource.REMOTE,
            loadedAt = Instant.parse("2026-06-01T00:00:00Z"),
            trustAnchors = listOf(chain.root),
        )
        assertTrue(snapshot.entities.containsKey("te-ec-access-ca-001"))
        assertEquals(1, snapshot.trustAnchors.size)
    }
}
