package di.swallet.wpb.openid4vp.protocol

import di.swallet.wpb.presentation.domain.ClaimPathSegment
import di.swallet.wpb.presentation.domain.CredentialFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DcqlSupportTest {

    @Test
    fun `parses standard DCQL credentials shape`() {
        val json = """
            {
              "credentials": [
                {
                  "id": "pid",
                  "format": "vc+sd-jwt",
                  "meta": {"vct_values": ["PID"]},
                  "claims": [
                    {"path": ["given_name"]},
                    {"path": ["family_name"]}
                  ]
                }
              ]
            }
        """.trimIndent()

        val queries = DcqlSupport.parse(json)
        assertEquals(1, queries.size)
        val q = queries.single()
        assertEquals("pid", q.id)
        assertEquals(CredentialFormat.SD_JWT, q.format)
        assertEquals(listOf("PID"), q.credentialTypeHints)
        assertEquals(listOf("given_name", "family_name"), q.requestedClaims)
        assertEquals(
            listOf(listOf("given_name"), listOf("family_name")),
            q.requestedClaimPaths.map { path -> path.segments.map { it.toPathString() } },
        )
    }

    @Test
    fun `parses multi-segment DCQL path`() {
        val json = """
            {
              "credentials": [{
                "id": "pid",
                "format": "vc+sd-jwt",
                "claims": [{ "path": ["address", "locality"] }]
              }]
            }
        """.trimIndent()
        val path = DcqlSupport.parse(json).single().requestedClaimPaths.single()
        assertEquals(listOf("address", "locality"), path.segments.map { it.toPathString() })
        assertEquals("address.locality", path.toDotNotation())
    }

    @Test
    fun `parses array wildcard segment as null`() {
        val json = """
            {
              "credentials": [{
                "id": "pid",
                "format": "vc+sd-jwt",
                "claims": [{ "path": ["nationalities", null] }]
              }]
            }
        """.trimIndent()
        val segments = DcqlSupport.parse(json).single().requestedClaimPaths.single().segments
        assertEquals(ClaimPathSegment.Key("nationalities"), segments[0])
        assertEquals(ClaimPathSegment.Wildcard, segments[1])
    }

    @Test
    fun `parses emulator query shape and synthesises ids`() {
        val json = """
            { "query": [{ "type": "Credential", "fields": ["name", "country"] }] }
        """.trimIndent()

        val queries = DcqlSupport.parse(json)
        assertEquals(1, queries.size)
        val q = queries.single()
        assertEquals("query_0", q.id)
        assertEquals(listOf("name", "country"), q.requestedClaims)
        assertEquals(CredentialFormat.SD_JWT, q.format)
    }

    @Test
    fun `falls back to empty list on invalid input`() {
        assertTrue(DcqlSupport.parse(null).isEmpty())
        assertTrue(DcqlSupport.parse("").isEmpty())
        assertTrue(DcqlSupport.parse("not-json").isEmpty())
        assertTrue(DcqlSupport.parse("{}").isEmpty())
    }

    @Test
    fun `recognises mdoc format mapping`() {
        val json = """{"credentials":[{"id":"q","format":"mso_mdoc"}]}"""
        val queries = DcqlSupport.parse(json)
        assertEquals(CredentialFormat.MDOC, queries.single().format)
    }
}
