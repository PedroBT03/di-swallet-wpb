package di.swallet.wpb.trust.lote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LoteTrustParserTest {
    private val parser = LoteTrustParser()

    @Test
    fun `entity with multiple services only keeps active ones`() {
        val payload =
            """
            {
              "ListAndSchemeInformation": {
                "LoTESequenceNumber": 42,
                "ListIssueDateTime": "2026-06-01T00:00:00Z",
                "NextUpdate": {"dateTime": "2026-07-01T00:00:00Z"},
                "LoTEType": "http://uri.etsi.org/19602/LoTEType/EU/PIDProviders",
                "StatusDeterminationApproach": "http://uri.etsi.org/19602/StatusDetn/EU"
              },
              "TrustedEntitiesList": [
                {
                  "TrustedEntityInformation": { "TEIdentifier": "entity-1" },
                  "TrustedEntityServices": [
                    {
                      "ServiceInformation": {
                        "ServiceTypeIdentifier": "urn:svc:active",
                        "ServiceStatus": "http://uri.etsi.org/19602/Status/granted",
                        "ServiceIdentifier": "svc-active"
                      }
                    },
                    {
                      "ServiceInformation": {
                        "ServiceTypeIdentifier": "urn:svc:inactive",
                        "ServiceStatus": "http://uri.etsi.org/19602/Status/withdrawn",
                        "ServiceIdentifier": "svc-inactive"
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val document = parser.parseDocument(payload)
        val entity = document.entities.single()
        assertEquals("entity-1", entity.entityId)
        assertEquals("urn:svc:active", entity.metadata["etsi.serviceType"])
        assertEquals("http://uri.etsi.org/19602/Status/granted", entity.metadata["etsi.serviceStatus"])
        assertFalse((entity.metadata["etsi.serviceIdentifiers"] ?: "").contains("svc-inactive"))
    }

    @Test
    fun `parser preserves mandatory ETSI metadata`() {
        val payload =
            """
            {
              "ListAndSchemeInformation": {
                "LoTESequenceNumber": 123,
                "ListIssueDateTime": "2026-01-02T03:04:05Z",
                "NextUpdate": {"dateTime": "2026-12-31T00:00:00Z"},
                "LoTEType": "urn:lote:type",
                "StatusDeterminationApproach": "urn:scheme:type"
              },
              "TrustedEntitiesList": [
                {
                  "TrustedEntityInformation": { "TEIdentifier": "entity-2" },
                  "TrustedEntityServices": [
                    {
                      "ServiceInformation": {
                        "ServiceTypeIdentifier": "urn:svc:one",
                        "ServiceStatus": "granted",
                        "ServiceIdentifier": "svc-1"
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val document = parser.parseDocument(payload)
        val snapshot = parser.toTrustSnapshot(
            document = document,
            source = LoteTrustSource.REMOTE,
            loadedAt = Instant.parse("2026-06-01T00:00:00Z"),
            trustAnchors = emptyList(),
        )
        val metadata = snapshot.entities["entity-2"]?.metadata.orEmpty()
        assertEquals("123", metadata["etsi.sequenceNumber"])
        assertEquals("2026-01-02T03:04:05Z", metadata["etsi.issueDate"])
        assertEquals("2026-12-31T00:00:00Z", metadata["etsi.nextUpdate"])
        assertEquals("urn:lote:type", metadata["etsi.listType"])
        assertEquals("urn:scheme:type", metadata["etsi.schemeType"])
        assertTrue((metadata["etsi.services.json"] ?: "").contains("svc-1"))
    }
}
