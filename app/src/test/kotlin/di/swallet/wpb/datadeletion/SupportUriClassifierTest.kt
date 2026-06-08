package di.swallet.wpb.datadeletion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SupportUriClassifierTest {
    private val classifier = SupportUriClassifier()

    @Test
    fun `classifies mailto tel and https`() {
        assertEquals(DeletionContactChannel.EMAIL, classifier.classify("mailto:info@rp.eu")?.channel)
        assertEquals("info@rp.eu", classifier.classify("mailto:info@rp.eu")?.value)
        assertEquals(DeletionContactChannel.PHONE, classifier.classify("tel:+48111222333")?.channel)
        assertEquals(DeletionContactChannel.WEB, classifier.classify("https://rp.eu/privacy")?.channel)
    }

    @Test
    fun `ignores blank values`() {
        assertNull(classifier.classify("   "))
    }
}
