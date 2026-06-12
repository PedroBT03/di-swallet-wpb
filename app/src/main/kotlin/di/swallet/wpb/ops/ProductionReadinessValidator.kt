package di.swallet.wpb.ops

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Fail-fast guardrails when the `prod` profile is active.
 */
@Component
@Profile("prod")
class ProductionReadinessValidator(
    private val environment: Environment,
    private val openId4VpProperties: OpenId4VpProperties,
    private val openId4VciProperties: OpenId4VciProperties,
    private val walletProperties: WalletProperties,
    private val transactionLogProperties: TransactionLogProperties,
    private val dpaReportProperties: DpaReportProperties,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        val violations = mutableListOf<String>()

        if (openId4VpProperties.demoMode) {
            violations += "wpb.openid4vp.demo-mode must be false in prod"
        }
        if (openId4VciProperties.demoMode) {
            violations += "wpb.openid4vci.demo-mode must be false in prod"
        }
        if (walletProperties.allowUntrustedAttestation) {
            violations += "wallet.allow-untrusted-attestation must be false in prod"
        }

        val hsmPin = environment.getProperty("wpb.hsm.pin").orEmpty()
        if (hsmPin.isBlank() || hsmPin == KNOWN_WEAK_HSM_PIN) {
            violations += "wpb.hsm.pin must be set to a non-default secret"
        }

        val dbPassword = environment.getProperty("spring.datasource.password").orEmpty()
        if (dbPassword.isBlank() || dbPassword == KNOWN_WEAK_DB_PASSWORD) {
            violations += "spring.datasource.password must be set to a non-default secret"
        }

        if (walletProperties.disclosures.encryptionKey == KNOWN_WEAK_DISCLOSURE_KEY) {
            violations += "wallet.disclosures.encryption-key must be overridden in prod"
        }
        if (transactionLogProperties.encryptionKey == KNOWN_WEAK_TX_ENC_KEY) {
            violations += "wpb.transaction-log.encryption-key must be overridden in prod"
        }
        if (transactionLogProperties.integrityKey == KNOWN_WEAK_TX_INT_KEY) {
            violations += "wpb.transaction-log.integrity-key must be overridden in prod"
        }

        if (!openId4VciProperties.ka.enforceProductionTrustPolicy) {
            logger.warn("prod readiness warning: wpb.openid4vci.ka.enforce-production-trust-policy is false")
        }
        if (dpaReportProperties.providerFallbackDpa.email.isBlank()) {
            logger.warn("prod readiness warning: wpb.dpa-reporting.provider-fallback-dpa.email is not configured (RPT_DPA_01)")
        }

        if (violations.isNotEmpty()) {
            val message = "Production readiness check failed:\n- " + violations.joinToString("\n- ")
            logger.error(message)
            throw IllegalStateException(message)
        }
        logger.info("event=production.readiness.passed profiles={}", environment.activeProfiles.joinToString())
    }

    companion object {
        const val KNOWN_WEAK_HSM_PIN = "1234"
        const val KNOWN_WEAK_DB_PASSWORD = "tese2026"
        const val KNOWN_WEAK_DISCLOSURE_KEY = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY="
        const val KNOWN_WEAK_TX_ENC_KEY = "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
        const val KNOWN_WEAK_TX_INT_KEY = "YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI="
    }
}
