package di.swallet.wpb.trustmark

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrustMarkResourceValidatorTest {
    private val validator = TrustMarkResourceValidator()

    @Test
    fun `accepts resource with url and localizations`() {
        val result = validator.validate(
            TrustMarkResourcePayload(
                image = TrustMarkImageResource(url = "logo.png"),
                text = TrustMarkTextResource(localizations = mapOf("en" to "Certified wallet")),
            ),
        )
        assertTrue(result.valid)
    }

    @Test
    fun `rejects resource without localizations`() {
        val result = validator.validate(
            TrustMarkResourcePayload(
                image = TrustMarkImageResource(url = "logo.png"),
                text = TrustMarkTextResource(localizations = emptyMap()),
            ),
        )
        assertFalse(result.valid)
    }
}
