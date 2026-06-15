package di.swallet.wpb.transactionlog.crypto

import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.security.HolderLogKeyContext
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TransactionLogCryptoBlankKeyTest {

    @Test
    fun `rejects blank integrity key`() {
        val properties = TransactionLogProperties().apply {
            integrityKey = ""
            encryptionKey = "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransactionLogCrypto(properties, HolderLogKeyContext())
        }
    }

    @Test
    fun `rejects blank server encryption key when dek mode is server`() {
        val properties = TransactionLogProperties().apply {
            dekMode = "server"
            integrityKey = "YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI="
            encryptionKey = ""
        }
        val crypto = TransactionLogCrypto(properties, HolderLogKeyContext())
        assertThrows(IllegalArgumentException::class.java) {
            crypto.encrypt("holder-1", "payload".toByteArray())
        }
    }
}
