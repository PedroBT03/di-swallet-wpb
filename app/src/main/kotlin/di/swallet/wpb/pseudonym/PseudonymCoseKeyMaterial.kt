package di.swallet.wpb.pseudonym

import di.swallet.wpb.format.mdoc.MdocCoseKeyMaterial
import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Component
import java.security.interfaces.ECPublicKey
import java.util.Base64

@Component
class PseudonymCoseKeyMaterial(
    private val hsmService: HsmService,
) {
    fun publicKeyCoseBase64UrlFromAlias(keyAlias: String): String {
        val publicKey = hsmService.getDedicatedPublicKey(keyAlias)
        return encodeCosePublicKey(publicKey)
    }

    fun encodeCosePublicKey(publicKey: ECPublicKey): String {
        val cose = MdocCoseKeyMaterial.toCoseEc2PublicKey(publicKey)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cose.encode())
    }
}
