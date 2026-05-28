package di.swallet.wpb.issuance.crypto

import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.impl.ECDSA
import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Service

@Service
class JwsSigningService(
    private val hsmService: HsmService,
) {
    fun signJws(
        walletKey: WalletKey,
        header: com.nimbusds.jose.JWSHeader,
        payload: Map<String, Any?>,
    ): String {
        hsmService.validateKeyStatus(walletKey)
        val jwsObject = JWSObject(header, Payload(payload))
        val derSignature = hsmService.signData(walletKey.userId, jwsObject.signingInput)
        val concatSignature = ECDSA.transcodeSignatureToConcat(derSignature, 64)
        val b64Signature = Base64URL.encode(concatSignature)
        return "${header.toBase64URL()}.${jwsObject.payload.toBase64URL()}.$b64Signature"
    }
}
