/**
 * Tests production readiness validator.
 */

package di.swallet.wpb.ops

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.MdocProperties
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment

class ProductionReadinessValidatorTest {

    /**
     * Runs the prod-profile validator with demoMode enabled and weak HSM/DB secrets and
     * expects IllegalStateException because unsafe prod configuration must fail fast.
     */
    @Test
    fun `fails fast when demo mode or weak secrets remain in prod`() {
        val env = prodEnvironment()
        val validator = validator(
            environment = env,
            openId4VpProperties = OpenId4VpProperties().apply { demoMode = true },
        )
        assertThrows(IllegalStateException::class.java) { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Runs the prod validator with default bundled classpath signing key paths and expects
     * IllegalStateException because dev keys must not ship in production.
     */
    @Test
    fun `fails when bundled dev signing keys remain in prod`() {
        val validator = validator(environment = prodEnvironment())
        assertThrows(IllegalStateException::class.java) { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Configures prod with transaction log dekMode=holder and an otherwise weak server
     * encryption key, then expects validation to pass because holder DEK mode skips that check.
     */
    @Test
    fun `passes when prod uses holder dek mode even with default server encryption key`() {
        val validator = ProductionReadinessValidator(
            environment = healthyProdEnvironment(),
            openId4VpProperties = OpenId4VpProperties().apply { demoMode = false },
            openId4VciProperties = OpenId4VciProperties().apply { demoMode = false },
            walletProperties = WalletProperties(
                allowUntrustedAttestation = false,
                disclosures = WalletProperties.DisclosuresProperties(
                    encryptionKey = "cHJvZC1kaXNjbG9zdXJlLWtleS0zMmJ5dGVzbG9uZw==",
                ),
            ),
            transactionLogProperties = TransactionLogProperties().apply {
                dekMode = "holder"
                encryptionKey = WeakSecretDefaults.KNOWN_WEAK_TX_ENC_KEY
                integrityKey = "cHJvZC10eC1pbnQta2V5LXRoaXMyYnl0ZXMtbG9uZw=="
            },
            statusListProperties = externalSigningKeyProperties(),
            mdocProperties = externalMdocKeyProperties(),
            dpaReportProperties = configuredDpaFallback(),
        )
        assertDoesNotThrow { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Supplies prod profile with demo mode off, strong crypto secrets, external signing keys,
     * and a configured DPA fallback contact, then expects validation to complete cleanly.
     */
    @Test
    fun `passes when prod secrets and flags are overridden`() {
        val validator = ProductionReadinessValidator(
            environment = healthyProdEnvironment(),
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
            statusListProperties = externalSigningKeyProperties(),
            mdocProperties = externalMdocKeyProperties(),
            dpaReportProperties = configuredDpaFallback(),
        )
        assertDoesNotThrow { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Uses otherwise healthy prod settings but leaves provider fallback DPA email unset and
     * expects IllegalStateException because prod requires a fallback DPA contact.
     */
    @Test
    fun `fails when provider fallback DPA contact is not configured`() {
        val validator = ProductionReadinessValidator(
            environment = healthyProdEnvironment(),
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
            statusListProperties = externalSigningKeyProperties(),
            mdocProperties = externalMdocKeyProperties(),
            dpaReportProperties = DpaReportProperties(),
        )
        assertThrows(IllegalStateException::class.java) { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Builds a ProductionReadinessValidator with prod profile defaults and optional demo-mode
     * override on OpenId4VpProperties for unsafe-configuration failure tests.
     */
    private fun validator(
        environment: MockEnvironment = prodEnvironment(),
        openId4VpProperties: OpenId4VpProperties = OpenId4VpProperties().apply { demoMode = false },
    ): ProductionReadinessValidator = ProductionReadinessValidator(
        environment = environment,
        openId4VpProperties = openId4VpProperties,
        openId4VciProperties = OpenId4VciProperties().apply { demoMode = false },
        walletProperties = WalletProperties(),
        transactionLogProperties = TransactionLogProperties(),
        statusListProperties = StatusListProperties(),
        mdocProperties = MdocProperties(),
        dpaReportProperties = DpaReportProperties(),
    )

    /** Returns a MockEnvironment with prod profile and known weak HSM pin and DB password. */
    private fun prodEnvironment(): MockEnvironment = MockEnvironment().apply {
        setActiveProfiles("prod")
        setProperty("wpb.hsm.pin", WeakSecretDefaults.KNOWN_WEAK_HSM_PIN)
        setProperty("spring.datasource.password", WeakSecretDefaults.KNOWN_WEAK_DB_PASSWORD)
    }

    /** Returns a MockEnvironment with prod profile and non-default HSM and DB secrets. */
    private fun healthyProdEnvironment(): MockEnvironment = MockEnvironment().apply {
        setActiveProfiles("prod")
        setProperty("wpb.hsm.pin", "prod-hsm-pin-secret")
        setProperty("spring.datasource.password", "prod-db-password-secret")
    }

    /** Configures status-list signing to use an external file path with auto-generation disabled. */
    private fun externalSigningKeyProperties(): StatusListProperties =
        StatusListProperties().apply {
            signingKeyPemPath = "file:/etc/wpb/status-list-signing-key.pem"
            autoGenerateSigningKeyIfMissing = false
        }

    /** Configures mdoc issuer key to use an external file path with auto-generation disabled. */
    private fun externalMdocKeyProperties(): MdocProperties =
        MdocProperties().apply {
            issuerKeyPemPath = "file:/etc/wpb/mdoc-issuer-key.pem"
            autoGenerateIssuerKeyIfMissing = false
        }

    /** Supplies a DpaReportProperties instance with a configured provider fallback email. */
    private fun configuredDpaFallback(): DpaReportProperties =
        DpaReportProperties().apply {
            providerFallbackDpa.email = "dpa-fallback@example.com"
        }
}
