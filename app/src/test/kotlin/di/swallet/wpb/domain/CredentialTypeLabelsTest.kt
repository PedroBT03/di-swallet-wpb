/**
 * Tests credential type label normalization.
 */

package di.swallet.wpb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CredentialTypeLabelsTest {

    @Test
    fun `displayLabel maps pid_jwt to PID`() {
        assertEquals("PID", CredentialTypeLabels.displayLabel("pid_jwt"))
    }

    @Test
    fun `canonicalWalletType maps configuration id to PID`() {
        assertEquals("PID", CredentialTypeLabels.canonicalWalletType("pid_jwt"))
    }

    @Test
    fun `displayLabel humanizes other configuration ids`() {
        assertEquals("Academic Card", CredentialTypeLabels.displayLabel("academic_card"))
    }
}
