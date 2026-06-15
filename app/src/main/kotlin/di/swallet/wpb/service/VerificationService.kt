/**
 * Verifies SD-JWT presentation signatures and validates disclosed claims against signed hashes.
 */

package di.swallet.wpb.service.verification

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.format.sdjwt.SdJwtService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.*

/**
 * Acts as relying-party verification logic for SD-JWT signatures and selective disclosure integrity.
 */
@Service
class VerificationService(
    private val sdJwtService: SdJwtService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Verifies the JWS signature and returns claim values whose disclosure hashes appear in the JWT.
     */
    fun verifyPresentation(sdJwt: String, publicKeyBase64: String): Map<String, Any> {
        val parts = sdJwt.split("~")
        val signedJwt = parts[0]
        val disclosures = parts.subList(1, parts.size).filter { it.isNotEmpty() }

        // 1. Verify JWS Signature using the provided public key
        val jwsObject = JWSObject.parse(signedJwt)
        val publicKey = decodePublicKey(publicKeyBase64)
        val verifier = ECDSAVerifier(publicKey)

        if (!jwsObject.verify(verifier)) {
            throw RuntimeException("Invalid JWS Signature: The token was not signed by this user's key.")
        }

        // 2. Parse Payload and Validate Disclosures
        val payload = jwsObject.payload.toJSONObject()
        val sdHashes = payload["_sd"] as? List<*> ?: emptyList<String>()
        val verifiedClaims = mutableMapOf<String, Any>()

        for (disclosure in disclosures) {
            val hash = sdJwtService.hashDisclosure(disclosure)
            if (sdHashes.contains(hash)) {
                // Decode disclosure: [salt, name, value]
                val jsonArray = String(Base64URL(disclosure).decode())
                val disclosureList = objectMapper.readValue(jsonArray, List::class.java)
                
                val claimName = disclosureList[1] as String
                val claimValue = disclosureList[2] ?: continue 
                
                verifiedClaims[claimName] = claimValue
            }
        }

        logger.info("Verifier: Successfully verified ${verifiedClaims.size} claims.")
        return verifiedClaims
    }

    /**
     * Decodes a Base64URL-encoded EC public key for JWS signature verification.
     */
    private fun decodePublicKey(base64Key: String): ECPublicKey {
        val keyBytes = Base64.getUrlDecoder().decode(base64Key.trim())
        val spec = X509EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(spec) as ECPublicKey
    }
}
