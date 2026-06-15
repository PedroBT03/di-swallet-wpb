/**
 * Tests trust mark localization service.
 */

package di.swallet.wpb.trustmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrustMarkLocalizationServiceTest {
    private val service = TrustMarkLocalizationService()

    /**
     * Localizations exist for en and pt but the requested language is de with default en.
     * select must return the default en language and English text.
     */
    @Test
    fun `falls back to default language`() {
        val (lang, text) = service.select(
            localizations = mapOf("en" to "Trust Mark", "pt" to "Marca de confiança"),
            requestedLanguage = "de",
            defaultLanguage = "en",
        )
        assertEquals("en", lang)
        assertEquals("Trust Mark", text)
    }

    /**
     * Localizations exist for en and pt and the requested language is pt.
     * select must return pt and the Portuguese localized text.
     */
    @Test
    fun `uses requested language when available`() {
        val (lang, text) = service.select(
            localizations = mapOf("en" to "Trust Mark", "pt" to "Marca de confiança"),
            requestedLanguage = "pt",
            defaultLanguage = "en",
        )
        assertEquals("pt", lang)
        assertEquals("Marca de confiança", text)
    }
}
