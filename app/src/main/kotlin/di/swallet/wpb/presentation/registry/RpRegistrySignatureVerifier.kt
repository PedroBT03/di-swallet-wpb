/**
 * Verifies signed JWT envelopes returned by the TS5 RP registry.
 */

package di.swallet.wpb.presentation.registry

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.config.OpenId4VpProperties
import org.slf4j.LoggerFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.stereotype.Component
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** Verified registry JWT payload with parsed `data` claim. */
data class VerifiedRegistryPayload(
    val signedJwt: String,
    val payloadJson: JsonObject,
    val issuer: String,
    val issuedAtEpochSeconds: Long,
    val dataElement: JsonElement,
)

/**
 * Validates compact JWS responses from RP registry HTTP endpoints.
 */
@Component
class RpRegistrySignatureVerifier(
    private val properties: OpenId4VpProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val resourceLoader = DefaultResourceLoader()
    private val json = Json { ignoreUnknownKeys = true }
    private val keyCache = mutableListOf<PublicKey>()

    /**
     * Verifies signature, envelope claims, and issuer policy for a registry JWS body.
     */
    fun verifyCompactJws(compactJws: String, endpoint: String): VerifiedRegistryPayload {
        val jwt = SignedJWT.parse(compactJws)
        ensureSignatureValid(jwt)

        val claims = jwt.jwtClaimsSet
        val payloadObject = json.parseToJsonElement(jwt.payload.toString()) as? JsonObject
            ?: throw IllegalStateException("registry JWT payload is not a JSON object")

        val requireEnvelope = properties.registry.requireSignedEnvelopeFields
        val iss = claims.issuer?.takeIf { it.isNotBlank() } ?: run {
            if (requireEnvelope) throw IllegalStateException("registry JWT missing mandatory 'iss' claim")
            ""
        }
        val iatSeconds = claims.issueTime?.toInstant()?.epochSecond
            ?: payloadObject["iat"]?.jsonPrimitive?.longOrNull
            ?: run {
                if (requireEnvelope) throw IllegalStateException("registry JWT missing mandatory 'iat' claim")
                Instant.now().epochSecond
            }
        val data = payloadObject["data"] ?: run {
            if (requireEnvelope) throw IllegalStateException("registry JWT missing mandatory 'data' claim")
            JsonObject(emptyMap())
        }

        val allowedIssuers = properties.registry.allowedIssuers()
        if (allowedIssuers.isNotEmpty() && iss.isNotBlank() && iss !in allowedIssuers) {
            throw IllegalStateException("registry JWT issuer '$iss' is not allow-listed")
        }
        val jti = claims.jwtid
        if (jti != null && jti.isBlank()) {
            throw IllegalStateException("registry JWT contains empty 'jti' claim")
        }

        validateTemporalClaims(claims)
        validateAudienceIfConfigured(claims)
        logger.info("event=registry.jws.verified endpoint={} iss={} iat={}", endpoint, iss, iatSeconds)
        return VerifiedRegistryPayload(
            signedJwt = compactJws,
            payloadJson = payloadObject,
            issuer = iss,
            issuedAtEpochSeconds = iatSeconds,
            dataElement = data,
        )
    }

    /** Rejects expired or not-yet-valid registry JWTs within configured clock skew. */
    private fun validateTemporalClaims(claims: com.nimbusds.jwt.JWTClaimsSet) {
        val now = Instant.now()
        val skew = properties.registry.clockSkewSeconds.coerceAtLeast(0)
        claims.expirationTime?.toInstant()?.let { exp ->
            if (exp.plusSeconds(skew).isBefore(now)) {
                throw IllegalStateException("registry JWT is expired")
            }
        }
        claims.notBeforeTime?.toInstant()?.let { nbf ->
            if (nbf.minusSeconds(skew).isAfter(now)) {
                throw IllegalStateException("registry JWT not yet valid")
            }
        }
    }

    /** Validates the JWT audience when an expected audience is configured. */
    private fun validateAudienceIfConfigured(claims: com.nimbusds.jwt.JWTClaimsSet) {
        val expectedAud = properties.registry.expectedAudience.trim()
        if (expectedAud.isBlank()) return
        val audiences = claims.audience ?: emptyList()
        if (expectedAud !in audiences) {
            throw IllegalStateException("registry JWT audience mismatch")
        }
    }

    /** Verifies the JWS signature against configured registry verification keys. */
    private fun ensureSignatureValid(jwt: SignedJWT) {
        if (!properties.registry.requireSignedResponses) return
        val keys = loadVerificationKeys()
        if (keys.isEmpty()) throw IllegalStateException("no registry verification keys configured")
        val ok = keys.any { key ->
            when (key) {
                is ECPublicKey -> runCatching { jwt.verify(ECDSAVerifier(key)) }.getOrDefault(false)
                is RSAPublicKey -> runCatching { jwt.verify(RSASSAVerifier(key)) }.getOrDefault(false)
                else -> false
            }
        }
        if (!ok) throw IllegalStateException("registry JWT signature validation failed")
    }

    /** Loads and caches PEM public keys or certificates from configured paths. */
    private fun loadVerificationKeys(): List<PublicKey> {
        if (keyCache.isNotEmpty()) return keyCache.toList()
        val keys = mutableListOf<PublicKey>()
        properties.registry.verificationKeyPemPaths().forEach { path ->
            val normalizedPath = if (path.startsWith("/") || path.startsWith("file:") || path.startsWith("classpath:")) {
                path
            } else {
                path
            }
            val resource = if (normalizedPath.startsWith("/")) {
                resourceLoader.getResource("file:$normalizedPath")
            } else {
                resourceLoader.getResource(normalizedPath)
            }
            val raw = resource.inputStream.bufferedReader().use { it.readText() }
            parsePemPublicKeyOrCert(raw)?.let(keys::add)
        }
        keyCache.clear()
        keyCache.addAll(keys)
        return keys
    }

    /** Parses either a PEM certificate or a PEM public key into a [PublicKey]. */
    private fun parsePemPublicKeyOrCert(raw: String): PublicKey? {
        val trimmed = raw.trim()
        return when {
            trimmed.contains("BEGIN CERTIFICATE") -> parseCertificate(trimmed)?.publicKey
            trimmed.contains("BEGIN PUBLIC KEY") -> parsePublicKey(trimmed)
            else -> null
        }
    }

    /** Parses an X.509 certificate from PEM text. */
    private fun parseCertificate(pem: String): X509Certificate? {
        val base64 = pem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s+".toRegex(), "")
        val der = Base64.getDecoder().decode(base64)
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** Parses a SubjectPublicKeyInfo PEM block into a [PublicKey]. */
    private fun parsePublicKey(pem: String): PublicKey? {
        val base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val der = Base64.getDecoder().decode(base64)
        val spec = X509EncodedKeySpec(der)
        return listOf("EC", "RSA").firstNotNullOfOrNull { alg ->
            runCatching { KeyFactory.getInstance(alg).generatePublic(spec) }.getOrNull()
        }
    }
}
