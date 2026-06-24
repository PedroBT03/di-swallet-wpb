/**
 * Helpers for signing Wallet Instance Attestation PoP JWTs in integration tests.
 */

package di.swallet.wpb.wia.validation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date

object WiaPopTestSupport {
    data class DeviceKeyMaterial(
        val publicJwkJson: String,
        val privateKey: ECPrivateKey,
    )

    /** Generates a fresh P-256 device key pair and returns the public JWK plus signing key. */
    fun generateDeviceKeyMaterial(): DeviceKeyMaterial {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8

        fun coordinate(value: java.math.BigInteger): String {
            val rawInput = value.toByteArray()
            val raw = if (rawInput.size > fieldSize) {
                rawInput.copyOfRange(rawInput.size - fieldSize, rawInput.size)
            } else {
                rawInput
            }
            val sized = if (raw.size == fieldSize) raw else ByteArray(fieldSize - raw.size) + raw
            return encoder.encodeToString(sized)
        }

        val publicJwkJson =
            """{"kty":"EC","crv":"P-256","x":"${coordinate(publicKey.w.affineX)}","y":"${coordinate(publicKey.w.affineY)}"}"""
        return DeviceKeyMaterial(
            publicJwkJson = publicJwkJson,
            privateKey = keyPair.private as ECPrivateKey,
        )
    }

    /** Signs a ts3/OID4VCI Appendix E PoP JWT with the device (WIA cnf) private key. */
    fun signPop(
        privateKey: ECPrivateKey,
        walletInstanceId: String,
        cnfJkt: String,
        audience: String = "issuer",
        ttlSeconds: Long = 300,
    ): String {
        val now = Instant.now()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("oauth-client-attestation-pop+jwt"))
            .build()
        val claims = JWTClaimsSet.Builder()
            .issuer(walletInstanceId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .audience(audience)
            .claim("cnf", mapOf("jkt" to cnfJkt))
            .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(privateKey))
        return jwt.serialize()
    }
}
