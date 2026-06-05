package di.swallet.wpb.format.mdoc

import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Component

interface MdocDeviceAuthSigner {
    fun signEs256(keyAlias: String, sigStructureBytes: ByteArray): ByteArray
}

@Component
class HsmMdocDeviceAuthSigner(
    private val hsmService: HsmService,
) : MdocDeviceAuthSigner {
    override fun signEs256(keyAlias: String, sigStructureBytes: ByteArray): ByteArray =
        hsmService.signCoseEs256WithAlias(keyAlias, sigStructureBytes)
}
