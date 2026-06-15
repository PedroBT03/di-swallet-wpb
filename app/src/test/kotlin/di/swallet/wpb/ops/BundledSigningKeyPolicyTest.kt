package di.swallet.wpb.ops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BundledSigningKeyPolicyTest {

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
