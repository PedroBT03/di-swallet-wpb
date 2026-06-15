/**
 * Signs and publishes the wallet provider status list as a compressed Token Status List JWT.
 */

package di.swallet.wpb.revocation

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Encodes the current revocation bitstring into a signed Token Status List JWT per IETF draft.
 */
@Component
class StatusListJwtEncoder(
    private val statusListService: StatusListService,
    private val signingKeyStore: StatusListSigningKeyStore,
    private val properties: StatusListProperties,
) {
    /**
     * Builds a signed JWT containing the compressed status list bitstring and publisher metadata.
     */
    fun encode(): String {
        val now = Instant.now()
        val exp = now.plusSeconds(properties.jwtTtlSeconds)
        val compressed = compress(statusListService.getRawBitstringBytes())
        val lst = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)

        val claims = JWTClaimsSet.Builder()
            .issuer(properties.publicBaseUrl)
            .subject(statusListService.getListId())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .claim("status_list", mapOf("bits" to 1, "lst" to lst))
            .build()

        val material = signingKeyStore.material()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .x509CertChain(listOf(com.nimbusds.jose.util.Base64URL.encode(material.certificate.encoded)))
            .build()
        val signedJwt = SignedJWT(header, claims)
        signedJwt.sign(ECDSASigner(material.privateKey))
        return signedJwt.serialize()
    }

    /**
     * Compresses the raw bitstring bytes with DEFLATE for inclusion in the JWT `lst` claim.
     */
    private fun compress(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(input) }
        return output.toByteArray()
    }
}
