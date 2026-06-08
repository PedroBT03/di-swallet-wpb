package di.swallet.wpb.trustmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrustMarkLocalizationServiceTest {
    private val service = TrustMarkLocalizationService()

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
