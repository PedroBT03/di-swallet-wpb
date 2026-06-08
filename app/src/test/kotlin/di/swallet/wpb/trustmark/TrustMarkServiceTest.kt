package di.swallet.wpb.trustmark

import di.swallet.wpb.config.TrustMarkProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class TrustMarkServiceTest {
    private val properties = TrustMarkProperties()
    private val stubProvider = StubTrustMarkResourceProvider()
    private lateinit var service: TrustMarkService

    @BeforeEach
    fun setUp() {
        properties.enabled = true
        properties.trustMarkResourceUrl = "https://ec.example/trustmark/resource.json"
        properties.listOfCertifiedWalletsUrl = "https://ec.example/wallets"
        properties.walletSolutionInfoPageUrl = "https://ec.example/wallets?solution=abc"
        properties.walletSolutionId = "abc"
        properties.defaultLanguage = "en"
        service = TrustMarkService(
            properties = properties,
            resourceClient = stubProvider,
            urlResolver = TrustMarkUrlResolver(),
            localizationService = TrustMarkLocalizationService(),
            validator = TrustMarkResourceValidator(),
        )
    }

    @Test
    fun `returns disabled view when not configured`() {
        properties.enabled = false
        val view = service.getView("en")
        assertFalse(view.enabled)
        assertNotNull(view.userNotice)
    }

    @Test
    fun `returns enabled view with actions and localized resource`() {
        val view = service.getView("fr")
        assertTrue(view.enabled)
        assertEquals(2, view.actions.size)
        assertEquals("fr", view.resource?.language)
        assertEquals("Marque de confiance", view.resource?.localizedText)
        assertEquals("https://ec.example/trustmark/images/logo.png", view.resource?.imageUrl)
    }

    private class StubTrustMarkResourceProvider : TrustMarkResourceProvider {
        override fun getResource(forceRefresh: Boolean): CachedTrustMarkResource =
            CachedTrustMarkResource(
                payload = TrustMarkResourcePayload(
                    image = TrustMarkImageResource(name = "logo.png", url = "images/logo.png"),
                    text = TrustMarkTextResource(
                        localizations = mapOf(
                            "en" to "Trust Mark",
                            "fr" to "Marque de confiance",
                        ),
                    ),
                ),
                fetchedAt = Instant.parse("2025-07-29T09:00:00Z"),
                cacheSource = "test",
                expiresAt = Instant.parse("2025-07-29T10:00:00Z"),
            )

        override fun invalidate() = Unit
    }
}
