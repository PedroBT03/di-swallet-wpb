package di.swallet.wpb.ops

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class ProductionReadinessValidatorTest {

    @Test
    fun `fails fast when demo mode or weak secrets remain in prod`() {
        val env = MockEnvironment().apply {
            setActiveProfiles("prod")
            setProperty("wpb.hsm.pin", ProductionReadinessValidator.KNOWN_WEAK_HSM_PIN)
            setProperty("spring.datasource.password", ProductionReadinessValidator.KNOWN_WEAK_DB_PASSWORD)
        }
        val validator = validator(
            openId4VpProperties = OpenId4VpProperties().apply { demoMode = true },
        )
        assertThrows(IllegalStateException::class.java) { validator.run(null) }
    }

    @Test
    fun `passes when prod secrets and flags are overridden`() {
        val env = MockEnvironment().apply {
            setActiveProfiles("prod")
            setProperty("wpb.hsm.pin", "prod-pin-secret")
            setProperty("spring.datasource.password", "prod-db-secret")
        }
        val validator = ProductionReadinessValidator(
            environment = env,
            openId4VpProperties = OpenId4VpProperties().apply { demoMode = false },
            openId4VciProperties = OpenId4VciProperties().apply { demoMode = false },
            walletProperties = WalletProperties(
                allowUntrustedAttestation = false,
                disclosures = WalletProperties.DisclosuresProperties(
                    encryptionKey = "cHJvZC1kaXNjbG9zdXJlLWtleS0zMmJ5dGVzbG9uZw==",
                ),
            ),
            transactionLogProperties = TransactionLogProperties().apply {
                encryptionKey = "cHJvZC10eC1lbmMta2V5LXRoaXMyYnl0ZXMtbG9uZw=="
                integrityKey = "cHJvZC10eC1pbnQta2V5LXRoaXMyYnl0ZXMtbG9uZw=="
            },
            dpaReportProperties = DpaReportProperties(),
        )
        assertDoesNotThrow { validator.run(null) }
    }

    private fun validator(
        openId4VpProperties: OpenId4VpProperties = OpenId4VpProperties().apply { demoMode = false },
    ): ProductionReadinessValidator {
        val env = MockEnvironment().apply {
            setActiveProfiles("prod")
            setProperty("wpb.hsm.pin", ProductionReadinessValidator.KNOWN_WEAK_HSM_PIN)
            setProperty("spring.datasource.password", ProductionReadinessValidator.KNOWN_WEAK_DB_PASSWORD)
        }
        return ProductionReadinessValidator(
            environment = env,
            openId4VpProperties = openId4VpProperties,
            openId4VciProperties = OpenId4VciProperties().apply { demoMode = false },
            walletProperties = WalletProperties(),
            transactionLogProperties = TransactionLogProperties(),
            dpaReportProperties = DpaReportProperties(),
        )
    }
}
