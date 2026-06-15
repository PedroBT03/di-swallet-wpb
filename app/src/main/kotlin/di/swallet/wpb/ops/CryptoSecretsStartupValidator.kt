package di.swallet.wpb.ops

import di.swallet.wpb.config.SecurityProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Rejects known weak disclosure and transaction-log keys unless explicitly opted in (local dev / CI).
 */
@Component
@Order(1)
class CryptoSecretsStartupValidator(
    private val walletProperties: WalletProperties,
    private val transactionLogProperties: TransactionLogProperties,
    private val securityProperties: SecurityProperties,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        val violations = WeakCryptoSecretPolicy.violations(
            disclosureEncryptionKey = walletProperties.disclosures.encryptionKey,
            transactionLogEncryptionKey = transactionLogProperties.encryptionKey,
            transactionLogIntegrityKey = transactionLogProperties.integrityKey,
            dekMode = transactionLogProperties.resolvedDekMode(),
        )
        if (violations.isEmpty()) return

        if (securityProperties.allowKnownWeakCryptoSecrets) {
            logger.warn(
                "Known weak crypto secrets in use ({}). " +
                    "Override WALLET_DISCLOSURES_ENCRYPTION_KEY / WPB_TRANSACTION_LOG_* " +
                    "and set wpb.security.allow-known-weak-crypto-secrets=false before staging or production.",
                violations.joinToString(),
            )
            return
        }

        throw IllegalStateException(
            "Weak or missing crypto secrets: ${violations.joinToString()}. " +
                "Set strong keys via environment variables, or set " +
                "wpb.security.allow-known-weak-crypto-secrets=true for local/CI only.",
        )
    }
}
