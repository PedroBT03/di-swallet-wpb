package di.swallet.wpb.issuance.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * RFC 7638 JWK thumbprint over the canonical members {@code crv}, {@code kty}, {@code x}, {@code y}.
 */
object Rfc7638JwkThumbprint {
    private val objectMapper = ObjectMapper()

    fun fromJwkJson(jwkJson: String): String {
        val jwk = objectMapper.readTree(jwkJson.trim())
        require(jwk.path("kty").asText() == "EC") { "Only EC JWK thumbprints are supported" }
        val crv = jwk.path("crv").asText()
        val x = jwk.path("x").asText()
        val y = jwk.path("y").asText()
        val canonicalJwk = """{"crv":"$crv","kty":"EC","x":"$x","y":"$y"}"""
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJwk.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun fromEcPublicKey(publicKey: ECPublicKey): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8
        val x = encoder.encodeToString(coordinate(publicKey.w.affineX, fieldSize))
        val y = encoder.encodeToString(coordinate(publicKey.w.affineY, fieldSize))
        val canonicalJwk = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJwk.toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(digest)
    }

    fun fromPublicKeyBase64(publicKeyBase64: String): String {
        val bytes = Base64.getUrlDecoder().decode(publicKeyBase64)
        val spec = X509EncodedKeySpec(bytes)
        val key = KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
        return fromEcPublicKey(key)
    }

    private fun coordinate(value: java.math.BigInteger, size: Int): ByteArray {
        val rawInput = value.toByteArray()
        val raw = if (rawInput.size > size) rawInput.copyOfRange(rawInput.size - size, rawInput.size) else rawInput
        if (raw.size == size) return raw
        return ByteArray(size - raw.size) + raw
    }
}
