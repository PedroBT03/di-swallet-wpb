/**
 * Tests in memory mdoc device auth signer.
 */

package di.swallet.wpb.format.mdoc

import com.authlete.cose.COSESigner
import com.authlete.cose.constants.COSEAlgorithms
import java.security.interfaces.ECPrivateKey

class InMemoryMdocDeviceAuthSigner : MdocDeviceAuthSigner {
    private val keys = mutableMapOf<String, ECPrivateKey>()

    /** Stores an in-memory EC private key under [alias] for subsequent device-auth signing. */
    fun register(alias: String, privateKey: ECPrivateKey) {
        keys[alias] = privateKey
    }

    /** Signs [sigStructureBytes] with ES256 using the registered private key for [keyAlias]. */
    override fun signEs256(keyAlias: String, sigStructureBytes: ByteArray): ByteArray {
        val privateKey = keys[keyAlias]
            ?: throw IllegalStateException("No in-memory holder key registered for alias '$keyAlias'")
        return COSESigner.sign(privateKey, COSEAlgorithms.ES256, sigStructureBytes)
    }
}
