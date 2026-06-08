package di.swallet.wpb.trustmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrustMarkUrlResolverTest {
    private val resolver = TrustMarkUrlResolver()

    @Test
    fun `resolves relative image url against resource base`() {
        val resolved = resolver.resolveAgainstBase(
            "https://ec.example/trustmark/resource.json",
            "images/logo.png",
        )
        assertEquals("https://ec.example/trustmark/images/logo.png", resolved)
    }

    @Test
    fun `keeps absolute image url`() {
        val resolved = resolver.resolveAgainstBase(
            "https://ec.example/trustmark/resource.json",
            "https://cdn.example/logo.png",
        )
        assertEquals("https://cdn.example/logo.png", resolved)
    }
}
