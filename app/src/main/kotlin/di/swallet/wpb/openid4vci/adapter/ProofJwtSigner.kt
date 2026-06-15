/**
 * Signs OpenID4VCI proof JWTs with wallet-held keys.
 */

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

/**
 * Signs issuer-facing proof JWTs used during credential issuance.
 */
interface ProofJwtSigner {
    /**
     * Builds and signs a proof JWT for the given audience and optional c_nonce.
     */
    fun sign(
        proof: ProofMaterial,
        audience: String,
        cNonce: String?,
    ): String
}

/**
 * Signs proof JWTs through the wallet HSM using the holder's bound key.
 */
@Component
class HsmProofJwtSigner(
    private val hsmService: HsmService,
) : ProofJwtSigner {
    /**
     * Creates an ES256 proof JWT and signs it with the wallet key referenced by [proof].
     */
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

    /** Parses the holder user id embedded in a wallet key alias such as `key-user-1`. */
    private fun extractUserIdFromKeyAlias(keyAlias: String): String {
        val regex = Regex("^key-(.+)-\\d+$")
        val match = regex.matchEntire(keyAlias)
            ?: throw IllegalArgumentException("unsupported proof key alias format: $keyAlias")
        return match.groupValues[1]
    }
}
