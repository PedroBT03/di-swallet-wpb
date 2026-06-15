/**
 * Tests trust mark url resolver.
 */

package di.swallet.wpb.trustmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrustMarkUrlResolverTest {
    private val resolver = TrustMarkUrlResolver()

    /**
     * Image path is relative to a trust mark resource.json base URL.
     * resolveAgainstBase must produce a fully qualified URL under the same host and path prefix.
     */
    @Test
    fun `resolves relative image url against resource base`() {
        val resolved = resolver.resolveAgainstBase(
            "https://ec.example/trustmark/resource.json",
            "images/logo.png",
        )
        assertEquals("https://ec.example/trustmark/images/logo.png", resolved)
    }

    /**
     * Image URL is already an absolute CDN URL passed to resolveAgainstBase.
     * Resolver must return the URL unchanged.
     */
    @Test
    fun `keeps absolute image url`() {
        val resolved = resolver.resolveAgainstBase(
            "https://ec.example/trustmark/resource.json",
            "https://cdn.example/logo.png",
        )
        assertEquals("https://cdn.example/logo.png", resolved)
    }
}
