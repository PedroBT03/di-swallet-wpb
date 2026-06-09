package di.swallet.wpb.transactionlog

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
import di.swallet.wpb.transactionlog.crypto.Ts10JweEncoder
import di.swallet.wpb.transactionlog.domain.Ts10CredentialDeletion
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.domain.TransactionLogRepository
import di.swallet.wpb.transactionlog.export.MigrationObjectBuilder
import di.swallet.wpb.transactionlog.service.TransactionLogService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@Configuration
@EnableConfigurationProperties(TransactionLogProperties::class)
class TransactionLogServiceIntegrationTestConfig

@DataJpaTest
@Import(
    TransactionLogService::class,
    TransactionLogCrypto::class,
    Ts10JweEncoder::class,
    MigrationObjectBuilder::class,
    JacksonAutoConfiguration::class,
    TransactionLogServiceIntegrationTestConfig::class,
)
@TestPropertySource(
    properties = [
        "wpb.transaction-log.encryption-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
        "wpb.transaction-log.integrity-key=YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI=",
    ],
)
@ConformanceTest
class TransactionLogServiceIntegrationTest {
    @Autowired lateinit var service: TransactionLogService
    @Autowired lateinit var repository: TransactionLogRepository

    @Test
    @ConformanceScenario("transaction_log_export_deletion")
    fun `record list export and soft delete`() {
        val tx = Ts10Transaction(
            transactionIdentifier = "tx-integration-1",
            time = "2025-07-29T09:11:20",
            transactionType = Ts10TransactionType.CredentialDeletion.name,
            transactionResult = Ts10TransactionResult.Completed.name,
            credentialDeletion = Ts10CredentialDeletion(credentialIdentifier = "PID"),
        )
        service.record("holder-a", tx)

        assertEquals(1, service.list("holder-a").size)
        val loaded = service.get("holder-a", "tx-integration-1")
        assertEquals("PID", loaded.credentialDeletion?.credentialIdentifier)

        val jwe = service.exportSelected("holder-a", listOf("tx-integration-1"), "secret".toCharArray())
        assertTrue(jwe.isNotBlank())

        service.markDeletedByUser("holder-a", "tx-integration-1")
        assertEquals(0, service.list("holder-a").size)
        assertEquals(1, repository.count())
    }
}
