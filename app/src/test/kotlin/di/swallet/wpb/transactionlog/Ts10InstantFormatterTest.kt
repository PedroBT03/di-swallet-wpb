/**
 * Tests ts10 instant formatter.
 */

package di.swallet.wpb.transactionlog

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class Ts10InstantFormatterTest {
    /**
     * Formats a known UTC instant for TS10 transaction timestamps.
     * Output must start with the date-time portion and end with the Z suffix.
     */
    @Test
    fun `formats UTC timestamps with Z suffix`() {
        val formatted = Ts10InstantFormatter.format(Instant.parse("2025-07-29T09:11:20Z"))
        assertTrue(formatted.endsWith("Z"), "expected ISO 8601 UTC suffix, got $formatted")
        assertTrue(formatted.startsWith("2025-07-29T09:11:20"))
    }
}
