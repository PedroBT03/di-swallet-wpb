/**
 * Tests support uri classifier.
 */

package di.swallet.wpb.datadeletion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SupportUriClassifierTest {
    private val classifier = SupportUriClassifier()

    /**
     * Classifies mailto, tel, and https support URIs and expects EMAIL, PHONE, and WEB channels
     * with the parsed email address extracted from the mailto value.
     */
    @Test
    fun `classifies mailto tel and https`() {
        assertEquals(DeletionContactChannel.EMAIL, classifier.classify("mailto:info@rp.eu")?.channel)
        assertEquals("info@rp.eu", classifier.classify("mailto:info@rp.eu")?.value)
        assertEquals(DeletionContactChannel.PHONE, classifier.classify("tel:+48111222333")?.channel)
        assertEquals(DeletionContactChannel.WEB, classifier.classify("https://rp.eu/privacy")?.channel)
    }

    /**
     * Passes a whitespace-only URI to classify and expects null because blank contact values
     * are treated as absent.
     */
    @Test
    fun `ignores blank values`() {
        assertNull(classifier.classify("   "))
    }
}
