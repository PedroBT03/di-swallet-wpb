package di.swallet.wpb.openid4vci.adapter

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.impl.ECDSA
import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

interface ProofJwtSigner {
    fun sign(
        proof: ProofMaterial,
        audience: String,
        cNonce: String?,
    ): String
}

@Component
class HsmProofJwtSigner(
    private val hsmService: HsmService,
) : ProofJwtSigner {
    override fun sign(
        proof: ProofMaterial,
        audience: String,
        cNonce: String?,
    ): String {
        val userId = extractUserIdFromKeyAlias(proof.keyId)
        val walletKey = hsmService.getUserKey(userId)
        hsmService.validateKeyStatus(walletKey)
        require(walletKey.keyAlias == proof.keyId) {
            "proof key '${proof.keyId}' does not match wallet key alias '${walletKey.keyAlias}'"
        }
        val now = Instant.now()
        val claims = mutableMapOf<String, Any>(
            "iss" to proof.keyId,
            "aud" to audience,
            "iat" to now.epochSecond,
            "jti" to UUID.randomUUID().toString(),
        )
        if (!cNonce.isNullOrBlank()) {
            claims["nonce"] = cNonce
        }
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(proof.keyId)
            .type(JOSEObjectType("openid4vci-proof+jwt"))
            .build()
        val jws = JWSObject(header, Payload(claims))
        val derSignature = hsmService.signData(userId, jws.signingInput)
        val concat = ECDSA.transcodeSignatureToConcat(derSignature, 64)
        val signature = Base64URL.encode(concat)
        return "${header.toBase64URL()}.${jws.payload.toBase64URL()}.$signature"
    }

    private fun extractUserIdFromKeyAlias(keyAlias: String): String {
        val regex = Regex("^key-(.+)-\\d+$")
        val match = regex.matchEntire(keyAlias)
            ?: throw IllegalArgumentException("unsupported proof key alias format: $keyAlias")
        return match.groupValues[1]
    }
}
