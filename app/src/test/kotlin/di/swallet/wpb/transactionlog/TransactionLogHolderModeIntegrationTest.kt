package di.swallet.wpb.transactionlog

import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.security.HolderLogKeyContext
import di.swallet.wpb.security.WalletSecurityAttributes
import di.swallet.wpb.transactionlog.crypto.HolderLogKeyDerivation
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
import di.swallet.wpb.transactionlog.crypto.Ts10JweEncoder
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.service.TransactionLogService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

@Configuration
@EnableConfigurationProperties(TransactionLogProperties::class)
class TransactionLogHolderModeIntegrationTestConfig

@DataJpaTest
@Import(
    TransactionLogService::class,
    TransactionLogCrypto::class,
    HolderLogKeyContext::class,
    Ts10JweEncoder::class,
    JacksonAutoConfiguration::class,
    TransactionLogHolderModeIntegrationTestConfig::class,
)
@TestPropertySource(
    properties = [
        "wpb.transaction-log.dek-mode=holder",
        "wpb.transaction-log.encryption-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
        "wpb.transaction-log.integrity-key=YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI=",
    ],
)
class TransactionLogHolderModeIntegrationTest {
    @Autowired lateinit var service: TransactionLogService

    private val holderId = "holder-holder-mode"
    private val logKey = HolderLogKeyDerivation.derive(holderId, "integration-passphrase".toCharArray())

    @Test
    fun `service rejects decrypt without holder log key in holder dek mode`() {
        val transaction = Ts10Transaction(
            transactionIdentifier = "tx-holder-mode-1",
            time = "2025-07-29T09:11:20Z",
            transactionType = Ts10TransactionType.Presentation.name,
            transactionResult = Ts10TransactionResult.Completed.name,
        )

        withLogKey(logKey) {
            service.record(holderId, transaction)
        }

        assertThrows(ResponseStatusException::class.java) {
            service.get(holderId, transaction.transactionIdentifier)
        }

        withLogKey(logKey) {
            val loaded = service.get(holderId, transaction.transactionIdentifier)
            assertEquals(transaction.transactionIdentifier, loaded.transactionIdentifier)
        }
    }

    private fun <T> withLogKey(key: ByteArray, block: () -> T): T {
        val request = MockHttpServletRequest()
        request.setAttribute(WalletSecurityAttributes.HOLDER_LOG_KEY, key)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        return try {
            block()
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }
}
