/**
 * Tests trust mark resource validator.
 */

package di.swallet.wpb.trustmark

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrustMarkResourceValidatorTest {
    private val validator = TrustMarkResourceValidator()

    /**
     * Payload includes an image URL and a non-empty localizations map.
     * validate must report the resource as valid.
     */
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

    /**
     * Payload includes an image URL but an empty localizations map.
     * validate must report the resource as invalid.
     */
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
