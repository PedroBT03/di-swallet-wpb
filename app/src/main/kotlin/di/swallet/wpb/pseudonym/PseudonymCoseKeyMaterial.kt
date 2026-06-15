/**
 * Encodes HSM-held pseudonym public keys as COSE EC2 keys for WebAuthn and TS10 export.
 */

package di.swallet.wpb.pseudonym

import di.swallet.wpb.format.mdoc.MdocCoseKeyMaterial
import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Component
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Reads dedicated pseudonym keys from the HSM and exposes them as Base64URL COSE public keys.
 */
@Component
class PseudonymCoseKeyMaterial(
    private val hsmService: HsmService,
) {
    /**
     * Loads the public key for an HSM alias and returns its COSE encoding as Base64URL.
     */
    fun publicKeyCoseBase64UrlFromAlias(keyAlias: String): String {
        val publicKey = hsmService.getDedicatedPublicKey(keyAlias)
        return encodeCosePublicKey(publicKey)
    }

    /**
     * Encodes an EC public key as a COSE EC2 key in Base64URL form.
     */
    fun encodeCosePublicKey(publicKey: ECPublicKey): String {
        val cose = MdocCoseKeyMaterial.toCoseEc2PublicKey(publicKey)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cose.encode())
    }
}
