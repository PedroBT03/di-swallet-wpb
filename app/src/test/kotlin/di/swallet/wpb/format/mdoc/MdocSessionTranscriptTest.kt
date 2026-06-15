/**
 * Tests mdoc session transcript.
 */

package di.swallet.wpb.format.mdoc

import com.authlete.cbor.CBORDecoder
import di.swallet.wpb.config.MdocProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MdocSessionTranscriptTest {
    /**
     * legacy-aud-nonce mode encodes a CBOR map with aud and nonce from the handover inputs.
     */
    @Test
    fun `legacy mode builds aud and nonce map`() {
        val builder = MdocSessionTranscriptBuilder(MdocProperties().apply {
            sessionTranscriptMode = "legacy-aud-nonce"
        })
        val transcript = builder.buildSessionTranscript(
            MdocOpenId4VpHandover(
                clientId = "verifier-client",
                nonce = "nonce-abc",
                audience = "verifier-client",
            ),
        )
        val parsed = CBORDecoder(transcript.encode()).next().parse() as Map<*, *>
        assertEquals("verifier-client", parsed["aud"])
        assertEquals("nonce-abc", parsed["nonce"])
    }

    /**
     * Same handover with responseUri produces different CBOR bytes in openid4vp mode versus legacy-aud-nonce.
     */
    @Test
    fun `openid4vp mode differs from legacy for same handover inputs`() {
        val handover = MdocOpenId4VpHandover(
            clientId = "verifier-client",
            nonce = "nonce-abc",
            audience = "verifier-client",
            responseUri = "https://verifier.example/response",
        )
        val legacy = MdocSessionTranscriptBuilder(MdocProperties().apply {
            sessionTranscriptMode = "legacy-aud-nonce"
        }).buildSessionTranscript(handover).encode()
        val openid4vp = MdocSessionTranscriptBuilder(MdocProperties().apply {
            sessionTranscriptMode = "openid4vp"
        }).buildSessionTranscript(handover).encode()
        assertNotEquals(legacy.toList(), openid4vp.toList())
    }

    /**
     * hybrid mode with responseUri present matches openid4vp encoding byte-for-byte.
     */
    @Test
    fun `hybrid mode selects openid4vp when responseUri is present`() {
        val handover = MdocOpenId4VpHandover(
            clientId = "verifier-client",
            nonce = "nonce-abc",
            audience = "verifier-client",
            responseUri = "https://verifier.example/response",
        )
        val hybrid = MdocSessionTranscriptBuilder(MdocProperties().apply {
            sessionTranscriptMode = "hybrid"
        }).buildSessionTranscript(handover).encode()
        val openid4vp = MdocSessionTranscriptBuilder(MdocProperties().apply {
            sessionTranscriptMode = "openid4vp"
        }).buildSessionTranscript(handover).encode()
        assertEquals(openid4vp.toList(), hybrid.toList())
    }
}
