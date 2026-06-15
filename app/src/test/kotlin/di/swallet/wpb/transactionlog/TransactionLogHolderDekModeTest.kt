/**
 * Tests transaction log holder dek mode.
 */

package di.swallet.wpb.transactionlog

import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.security.HolderLogKeyContext
import di.swallet.wpb.testTransactionLogProperties
import di.swallet.wpb.security.WalletSecurityAttributes
import di.swallet.wpb.transactionlog.crypto.HolderLogKeyDerivation
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
import di.swallet.wpb.transactionlog.crypto.TransactionLogDekMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

class TransactionLogHolderDekModeTest {
    private val holderId = "holder-1"
    private val password = "user-log-passphrase".toCharArray()
    private val holderKey = HolderLogKeyDerivation.derive(holderId, password)

    /**
     * Encrypts plaintext in holder DEK mode with the holder log key bound on the request context.
     * Without that key, canDecrypt is false and decrypt fails; with the key, decrypted bytes match the original.
     */
    @Test
    fun `holder mode encrypts with supplied key and WPB cannot decrypt without it`() {
        val properties = testTransactionLogProperties { dekMode = "holder" }
        val crypto = crypto(properties)

        val plaintext = """{"transactionIdentifier":"tx-1"}""".toByteArray()
        val ciphertext = withLogKey(holderKey) {
            crypto.encrypt(holderId, plaintext, TransactionLogDekMode.HOLDER)
        }

        assertFalse(crypto.canDecrypt(TransactionLogDekMode.HOLDER))
        assertThrows(ResponseStatusException::class.java) {
            crypto.decrypt(holderId, ciphertext, TransactionLogDekMode.HOLDER)
        }

        val decrypted = withLogKey(holderKey) {
            crypto.decrypt(holderId, ciphertext, TransactionLogDekMode.HOLDER)
        }
        assertArrayEquals(plaintext, decrypted)
    }

    /**
     * Encrypts plaintext using the default server DEK mode without a holder log key.
     * Crypto reports decrypt capability and round-trips the payload unchanged.
     */
    @Test
    fun `server mode remains backward compatible`() {
        val crypto = crypto(testTransactionLogProperties())
        val plaintext = "legacy-payload".toByteArray()
        val ciphertext = crypto.encrypt(holderId, plaintext)
        assertTrue(crypto.canDecrypt(TransactionLogDekMode.SERVER))
        assertArrayEquals(plaintext, crypto.decrypt(holderId, ciphertext, TransactionLogDekMode.SERVER))
    }

    /** Constructs TransactionLogCrypto with a fresh HolderLogKeyContext for the given properties. */
    private fun crypto(properties: TransactionLogProperties): TransactionLogCrypto =
        TransactionLogCrypto(properties, HolderLogKeyContext())

    /** Runs a block with the holder log key bound on the current servlet request attributes. */
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
