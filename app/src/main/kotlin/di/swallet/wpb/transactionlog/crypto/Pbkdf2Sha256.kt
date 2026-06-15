package di.swallet.wpb.transactionlog.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Pbkdf2Sha256 {
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
