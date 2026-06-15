/**
 * Tests bundled signing key policy.
 */

package di.swallet.wpb.ops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BundledSigningKeyPolicyTest {

    /**
     * Points status-list and mdoc key paths at bundled classpath dev resources and expects
     * violations to list both wpb.status-list.signing-key-pem-path and wpb.mdoc.issuer-key-pem-path.
     */
    @Test
    fun `flags bundled classpath signing keys`() {
        val violations = BundledSigningKeyPolicy.violations(
            statusListSigningKeyPemPath = WeakSecretDefaults.KNOWN_DEV_STATUS_LIST_SIGNING_KEY_PATH,
            statusListAutoGenerate = false,
            mdocIssuerKeyPemPath = WeakSecretDefaults.KNOWN_DEV_MDOC_ISSUER_KEY_PATH,
            mdocAutoGenerate = false,
        )
        assertEquals(
            listOf(
                "wpb.status-list.signing-key-pem-path",
                "wpb.mdoc.issuer-key-pem-path",
            ),
            violations,
        )
    }

    /**
     * Uses file:/etc/... paths for status-list and mdoc issuer keys and expects the policy
     * check to return an empty violation list.
     */
    @Test
    fun `accepts external file paths`() {
        val violations = BundledSigningKeyPolicy.violations(
            statusListSigningKeyPemPath = "file:/etc/wpb/status-list-signing-key.pem",
            statusListAutoGenerate = false,
            mdocIssuerKeyPemPath = "file:/etc/wpb/mdoc-issuer-key.pem",
            mdocAutoGenerate = false,
        )
        assertTrue(violations.isEmpty())
    }
}
