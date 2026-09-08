/**
 * Tests crypto secrets startup validator.
 */

package di.swallet.wpb.ops

import di.swallet.wpb.config.SecurityProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

class CryptoSecretsStartupValidatorTest {

    /**
     * Configures known weak disclosure and transaction-log keys with allowKnownWeakCryptoSecrets
     * false and expects startup validation to throw IllegalStateException.
     */
    @Test
    fun `fails when weak disclosure key is used without opt-in`() {
        val validator = validator(allowKnownWeakCryptoSecrets = false)
        assertThrows(IllegalStateException::class.java) { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Uses the same known weak crypto secrets but sets allowKnownWeakCryptoSecrets=true and
     * expects startup validation to complete without throwing.
     */
    @Test
    fun `allows weak keys when explicitly opted in`() {
        val validator = validator(allowKnownWeakCryptoSecrets = true)
        assertDoesNotThrow { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Supplies base64-encoded strong disclosure, encryption, and integrity keys and expects
     * startup validation to pass without needing the weak-secrets opt-in flag.
     */
    @Test
    fun `passes with strong keys`() {
        val validator = CryptoSecretsStartupValidator(
            walletProperties = WalletProperties(
                disclosures = WalletProperties.DisclosuresProperties(
                    encryptionKey = "c3Ryb25nLWRpc2Nsb3N1cmUta2V5LTMyYnl0ZXMtbG9uZw==",
                ),
            ),
            transactionLogProperties = TransactionLogProperties().apply {
                encryptionKey = "c3Ryb25nLXR4LWVuYy1rZXktdGhpcnR5Mi1ieXRlcw=="
                integrityKey = "c3Ryb25nLXR4LWludC1rZXktdGhpcnR5Mi1ieXRlcw=="
            },
            securityProperties = SecurityProperties(),
        )
        assertDoesNotThrow { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Sets transaction log dekMode to holder while keeping a known weak server encryption key
     * and expects validation to pass because that key check is skipped in holder DEK mode.
     */
    @Test
    fun `skips weak server encryption key check in holder dek mode`() {
        val validator = CryptoSecretsStartupValidator(
            walletProperties = WalletProperties(
                disclosures = WalletProperties.DisclosuresProperties(
                    encryptionKey = "c3Ryb25nLWRpc2Nsb3N1cmUta2V5LTMyYnl0ZXMtbG9uZw==",
                ),
            ),
            transactionLogProperties = TransactionLogProperties().apply {
                dekMode = "holder"
                encryptionKey = WeakSecretDefaults.KNOWN_WEAK_TX_ENC_KEY
                integrityKey = "c3Ryb25nLXR4LWludC1rZXktdGhpcnR5Mi1ieXRlcw=="
            },
            securityProperties = SecurityProperties(),
        )
        assertDoesNotThrow { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Builds a CryptoSecretsStartupValidator with known weak disclosure and transaction-log
     * keys, toggling allowKnownWeakCryptoSecrets for opt-in versus fail-fast scenarios.
     */
    private fun validator(allowKnownWeakCryptoSecrets: Boolean): CryptoSecretsStartupValidator =
        CryptoSecretsStartupValidator(
            walletProperties = WalletProperties(
                disclosures = WalletProperties.DisclosuresProperties(
                    encryptionKey = WeakSecretDefaults.KNOWN_WEAK_DISCLOSURE_KEY,
                ),
            ),
            transactionLogProperties = TransactionLogProperties().apply {
                encryptionKey = WeakSecretDefaults.KNOWN_WEAK_TX_ENC_KEY
                integrityKey = WeakSecretDefaults.KNOWN_WEAK_TX_INT_KEY
            },
            securityProperties = SecurityProperties().apply {
                this.allowKnownWeakCryptoSecrets = allowKnownWeakCryptoSecrets
            },
        )
}
