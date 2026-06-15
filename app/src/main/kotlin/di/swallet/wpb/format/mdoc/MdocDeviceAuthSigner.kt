/**
 * HSM-backed ES256 signing for mdoc device authentication COSE structures.
 */

package di.swallet.wpb.format.mdoc

import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Component

/** Signs mdoc DeviceAuthentication COSE Sig_structure bytes with a holder key. */
interface MdocDeviceAuthSigner {
    /** Returns an ES256 signature over the provided COSE Sig_structure bytes. */
    fun signEs256(keyAlias: String, sigStructureBytes: ByteArray): ByteArray
}

/** Delegates device authentication signing to the wallet HSM service. */
@Component
class HsmMdocDeviceAuthSigner(
    private val hsmService: HsmService,
) : MdocDeviceAuthSigner {
    /** Signs with the HSM key referenced by alias using COSE ES256 transcoding. */
    override fun signEs256(keyAlias: String, sigStructureBytes: ByteArray): ByteArray =
        hsmService.signCoseEs256WithAlias(keyAlias, sigStructureBytes)
}
