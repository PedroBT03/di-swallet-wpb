/**
 * PBKDF2-HMAC-SHA256 key derivation helper.
 */

package di.swallet.wpb.transactionlog.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2WithHmacSHA256 key derivation with configurable iterations and length. */
object Pbkdf2Sha256 {
    /** Derives a symmetric key from password, salt, and iteration count. */
    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = 120_000,
        keyLengthBits: Int = 256,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }
}
