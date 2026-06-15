/**
 * Validation of OID4VCI issuer signed_metadata JWTs.
 */

package di.swallet.wpb.issuance.trust

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.springframework.stereotype.Component
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant

enum class IssuerMetadataPolicyMode {
    PREFER_SIGNED,
    REQUIRE_SIGNED,
    IGNORE_SIGNED,
}

/** Outcome of signed issuer metadata verification. */
data class SignedMetadataValidationResult(
    val acceptable: Boolean,
    val reason: String? = null,
)

/**
 * Validates OID4VCI issuer `signed_metadata` JWTs according to
 * [OpenId4VciProperties.sdk.metadataPolicy].
 */
@Component
class IssuerSignedMetadataValidator(
    private val properties: OpenId4VciProperties,
) {
    /** Applies the configured metadata policy to resolved issuer metadata. */
    fun validate(metadata: ResolvedIssuerMetadata): SignedMetadataValidationResult {
        return when (properties.sdk.metadataPolicyMode()) {
            IssuerMetadataPolicyMode.IGNORE_SIGNED -> SignedMetadataValidationResult(true)
            IssuerMetadataPolicyMode.PREFER_SIGNED -> validatePreferSigned(metadata)
            IssuerMetadataPolicyMode.REQUIRE_SIGNED -> validateRequireSigned(metadata)
        }
    }

    /** Accepts unsigned metadata but verifies the JWT when present. */
    private fun validatePreferSigned(metadata: ResolvedIssuerMetadata): SignedMetadataValidationResult {
        val jwt = metadata.signedMetadataJwt
        if (jwt.isNullOrBlank()) {
            return SignedMetadataValidationResult(
                acceptable = true,
                reason = "unsigned issuer metadata accepted (preferSigned)",
            )
        }
        return verifySignedMetadataJwt(jwt, metadata.credentialIssuerId)
    }

    /** Rejects metadata that lacks a signed_metadata JWT. */
    private fun validateRequireSigned(metadata: ResolvedIssuerMetadata): SignedMetadataValidationResult {
        val jwt = metadata.signedMetadataJwt
        if (jwt.isNullOrBlank()) {
            return SignedMetadataValidationResult(
                acceptable = false,
                reason = "signed_metadata required but absent",
            )
        }
        return verifySignedMetadataJwt(jwt, metadata.credentialIssuerId)
    }

    /** Verifies signature, issuer claim, and token time bounds with configured clock skew. */
    private fun verifySignedMetadataJwt(compactJwt: String, expectedIssuer: String): SignedMetadataValidationResult {
        if (expectedIssuer.isBlank()) {
            return SignedMetadataValidationResult(false, "credential_issuer identifier missing")
        }
        return try {
            val signed = SignedJWT.parse(compactJwt)
            val verifier = resolveVerifier(signed)
                ?: return SignedMetadataValidationResult(false, "signed_metadata has no verifiable key material")
            val verified = runCatching { signed.verify(verifier) }.getOrDefault(false)
            if (!verified) {
                return SignedMetadataValidationResult(false, "signed_metadata signature verification failed")
            }
            val claims = signed.jwtClaimsSet
            val credentialIssuer = claims.getStringClaim("credential_issuer") ?: claims.issuer
            if (credentialIssuer != expectedIssuer) {
                return SignedMetadataValidationResult(
                    false,
                    "signed_metadata credential_issuer '$credentialIssuer' does not match '$expectedIssuer'",
                )
            }
            val skew = properties.trust.clockSkewSeconds
            val now = Instant.now()
            claims.expirationTime?.toInstant()?.let { exp ->
                if (exp.isBefore(now.minusSeconds(skew))) {
                    return SignedMetadataValidationResult(false, "signed_metadata has expired")
                }
            }
            claims.notBeforeTime?.toInstant()?.let { nbf ->
                if (nbf.isAfter(now.plusSeconds(skew))) {
                    return SignedMetadataValidationResult(false, "signed_metadata is not yet valid")
                }
            }
            val issuedAt = claims.issueTime?.toInstant()
            if (issuedAt != null && issuedAt.isAfter(now.plusSeconds(skew))) {
                return SignedMetadataValidationResult(false, "signed_metadata iat is in the future")
            }
            SignedMetadataValidationResult(true)
        } catch (ex: Exception) {
            SignedMetadataValidationResult(false, "signed_metadata validation failed: ${ex.message}")
        }
    }

    /** Builds a JWS verifier from the JWT x5c chain or embedded JWK. */
    private fun resolveVerifier(signed: SignedJWT): com.nimbusds.jose.JWSVerifier? {
        val x5c = signed.header.x509CertChain
        if (!x5c.isNullOrEmpty()) {
            val certBytes = x5c.first().decode()
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(certBytes.inputStream()) as java.security.cert.X509Certificate
            return when (val key = cert.publicKey) {
                is ECPublicKey -> ECDSAVerifier(key)
                is RSAPublicKey -> RSASSAVerifier(key)
                else -> null
            }
        }
        val jwk = signed.header.jwk ?: return null
        return verifierForJwk(jwk)
    }

    /** Selects an ECDSA or RSA verifier for the given JWK. */
    private fun verifierForJwk(jwk: JWK): com.nimbusds.jose.JWSVerifier? = when (jwk) {
        is ECKey -> ECDSAVerifier(jwk.toECPublicKey())
        is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
        else -> null
    }
}

/** Maps the configured SDK metadata policy string to a typed mode. */
fun OpenId4VciProperties.SdkProperties.metadataPolicyMode(): IssuerMetadataPolicyMode =
    when (metadataPolicy.trim().lowercase()) {
        "requiresigned" -> IssuerMetadataPolicyMode.REQUIRE_SIGNED
        "ignoresigned" -> IssuerMetadataPolicyMode.IGNORE_SIGNED
        else -> IssuerMetadataPolicyMode.PREFER_SIGNED
    }
