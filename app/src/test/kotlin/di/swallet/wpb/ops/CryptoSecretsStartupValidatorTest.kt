package di.swallet.wpb.ops

import di.swallet.wpb.config.SecurityProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CryptoSecretsStartupValidatorTest {

    @Test
    fun `fails when weak disclosure key is used without opt-in`() {
        val validator = validator(allowKnownWeakCryptoSecrets = false)
        assertThrows(IllegalStateException::class.java) { validator.run(null) }
    }

    @Test
    fun `allows weak keys when explicitly opted in`() {
        val validator = validator(allowKnownWeakCryptoSecrets = true)
        assertDoesNotThrow { validator.run(null) }
    }

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
        assertDoesNotThrow { validator.run(null) }
    }

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
        assertDoesNotThrow { validator.run(null) }
    }

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
