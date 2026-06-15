/**
 * Tests dcql support.
 */

package di.swallet.wpb.openid4vp.protocol

import di.swallet.wpb.presentation.domain.ClaimPathSegment
import di.swallet.wpb.presentation.domain.CredentialFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DcqlSupportTest {

    /**
     * Standard DCQL credentials JSON with PID vct hints and two claim paths is parsed.
     * Single query exposes id, SD-JWT format, type hints, flat claim names, and path segments.
     */
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

    /**
     * DCQL claim path spans address and locality segments.
     * Parsed path lists both segments and dot-notation address.locality.
     */
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

    /**
     * DCQL path uses a null array wildcard under nationalities.
     * Second segment is parsed as ClaimPathSegment.Wildcard.
     */
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

    /**
     * Input uses the emulator query array instead of credentials.
     * Parser synthesizes query_0 with SD-JWT format and the listed field names.
     */
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

    /**
     * null, empty, invalid JSON, and empty-object inputs are passed to parse.
     * Each yields an empty query list.
     */
    @Test
    fun `falls back to empty list on invalid input`() {
        assertTrue(DcqlSupport.parse(null).isEmpty())
        assertTrue(DcqlSupport.parse("").isEmpty())
        assertTrue(DcqlSupport.parse("not-json").isEmpty())
        assertTrue(DcqlSupport.parse("{}").isEmpty())
    }

    /**
     * Credential entry declares format mso_mdoc.
     * Parsed query maps to CredentialFormat.MDOC.
     */
    @Test
    fun `recognises mdoc format mapping`() {
        val json = """{"credentials":[{"id":"q","format":"mso_mdoc"}]}"""
        val queries = DcqlSupport.parse(json)
        assertEquals(CredentialFormat.MDOC, queries.single().format)
    }
}
