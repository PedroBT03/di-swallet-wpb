/**
 * Derives per-holder transaction log AES keys from a user password.
 */

package di.swallet.wpb.transactionlog.crypto

import java.security.MessageDigest

/**
 * Derives the per-holder transaction log AES key from a user-held secret.
 * The WPI should run this locally and send only the derived key via X-Wallet-Log-Key.
 */
object HolderLogKeyDerivation {
    private const val ITERATIONS = 120_000

    /** PBKDF2-SHA256 key derivation using holder ID as salt. */
    fun derive(holderId: String, password: CharArray): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(holderId.toByteArray(Charsets.UTF_8))
        return Pbkdf2Sha256.deriveKey(password, salt, iterations = ITERATIONS)
    }
}
