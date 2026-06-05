package di.swallet.wpb.ka.validation

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.issuance.domain.KeyAttestation
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.ka.trust.KaCertificateFingerprint
import di.swallet.wpb.ka.trust.KaTrustMode
import di.swallet.wpb.ka.trust.KaTrustPolicy
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Component
import java.time.Instant

class KeyAttestationValidationException(
    val code: String,
    message: String,
) : RuntimeException(message)

interface KeyAttestationValidationService {
    fun validateTechnical(attestation: KeyAttestation, configuration: CredentialConfigurationDescriptor)
    fun validateTrust(
        attestation: KeyAttestation,
        configuration: CredentialConfigurationDescriptor? = null,
        metadata: ResolvedIssuerMetadata? = null,
    )
    fun validateBinding(attestation: KeyAttestation, proof: ProofMaterial)
}

@Component
class DefaultKeyAttestationValidationService(
    private val properties: OpenId4VciProperties,
    private val statusListService: StatusListService,
    private val certificateChainValidator: CertificateChainValidator,
) : KeyAttestationValidationService {
    override fun validateTechnical(attestation: KeyAttestation, configuration: CredentialConfigurationDescriptor) {
        if (attestation.tokenExpiresAt.isBefore(Instant.now())) {
            throw KeyAttestationValidationException("ka_expired", "Key attestation token has expired")
        }
        if (attestation.statusExpiresAt.isBefore(Instant.now())) {
            throw KeyAttestationValidationException("ka_status_expired", "Key attestation status has expired")
        }
        if (configuration.keyAttestationRequired && attestation.attestedJkt.isBlank()) {
            throw KeyAttestationValidationException("ka_binding_missing", "Device-bound configuration requires attested jkt")
        }
        if (attestation.keyStorage != properties.ka.keyStorage) {
            throw KeyAttestationValidationException("ka_key_storage_mismatch", "key_storage does not match configured policy")
        }
        if (statusListService.isRevoked(attestation.status.index)) {
            throw KeyAttestationValidationException("ka_revoked", "key attestation status is revoked")
        }
    }

    override fun validateTrust(
        attestation: KeyAttestation,
        configuration: CredentialConfigurationDescriptor?,
        metadata: ResolvedIssuerMetadata?,
    ) {
        val parsed = runCatching { SignedJWT.parse(attestation.jwt) }.getOrNull()
            ?: throw KeyAttestationValidationException("ka_jwt_invalid", "Key attestation JWT is malformed")
        val claims = parsed.jwtClaimsSet
        val now = Instant.now().epochSecond
        if (claims.issuer != properties.ka.issuer) {
            throw KeyAttestationValidationException("ka_iss_invalid", "key attestation issuer is not trusted")
        }
        val iat = claims.issueTime?.toInstant()?.epochSecond
            ?: throw KeyAttestationValidationException("ka_iat_missing", "key attestation iat is missing")
        if (iat > now + 60) {
            throw KeyAttestationValidationException("ka_iat_invalid", "key attestation iat is in the future")
        }
        val exp = claims.expirationTime?.toInstant()?.epochSecond
            ?: throw KeyAttestationValidationException("ka_exp_missing", "key attestation exp is missing")
        if (exp <= now) {
            throw KeyAttestationValidationException("ka_expired", "key attestation JWT expired")
        }
        if (configuration != null) {
            val cfgClaim = claims.getStringClaim("credential_configuration_id")
            if (cfgClaim.isNullOrBlank() || cfgClaim != configuration.id) {
                throw KeyAttestationValidationException("ka_configuration_mismatch", "credential configuration does not match attestation")
            }
        }
        if (metadata != null) {
            val aud = claims.audience?.firstOrNull()
            if (aud.isNullOrBlank() || aud != metadata.credentialIssuerId) {
                throw KeyAttestationValidationException("ka_aud_invalid", "key attestation audience mismatch")
            }
        }

        val x5c = resolveX5c(parsed, attestation)
        if (properties.ka.requireX5c && x5c.isEmpty()) {
            throw KeyAttestationValidationException("ka_x5c_missing", "x5c is required by trust policy")
        }
        if (x5c.isEmpty()) {
            throw KeyAttestationValidationException("ka_x5c_missing", "x5c is required by trust policy")
        }
        val certChain = runCatching { certificateChainValidator.parseDerBase64Chain(x5c) }.getOrElse {
            throw KeyAttestationValidationException("ka_x5c_invalid", "unable to parse x5c certificate chain")
        }
        val leaf = certChain.first()
        enforceFingerprintAllowList(leaf)
        val verified = runCatching { parsed.verify(ECDSAVerifier(leaf.publicKey as java.security.interfaces.ECPublicKey)) }.getOrDefault(false)
        if (!verified) {
            throw KeyAttestationValidationException("ka_signature_invalid", "key attestation JWS signature is invalid")
        }

        when (KaTrustPolicy.effectiveMode(properties)) {
            KaTrustMode.STRICT -> {
                val anchors = properties.ka.trustAnchorPemPaths()
                if (anchors.isEmpty()) {
                    throw KeyAttestationValidationException(
                        "ka_pkix_untrusted",
                        "strict trust mode requires configured trust anchors",
                    )
                }
                runCatching { certificateChainValidator.validatePkix(certChain, anchors) }
                    .getOrElse {
                        throw KeyAttestationValidationException("ka_pkix_untrusted", "x5c chain failed PKIX validation")
                    }
            }
            KaTrustMode.RELAXED -> Unit
        }
    }

    override fun validateBinding(attestation: KeyAttestation, proof: ProofMaterial) {
        val proofJkt = Rfc7638JwkThumbprint.fromEcPublicKey(proof.publicKey)
        if (attestation.attestedJkt.isBlank() || proofJkt.isBlank()) {
            throw KeyAttestationValidationException("ka_binding_missing", "attested/proof jkt is missing")
        }
        if (attestation.attestedJkt != proofJkt) {
            throw KeyAttestationValidationException("ka_binding_mismatch", "proof key does not match attested key")
        }
    }

    private fun resolveX5c(parsed: SignedJWT, attestation: KeyAttestation): List<String> {
        val header = parsed.header
        return header.x509CertChain?.map { it.toString() }
            ?: (header.customParams["x5c"] as? List<*>)?.mapNotNull { it?.toString() }
            ?: attestation.x5c
    }

    private fun enforceFingerprintAllowList(leaf: java.security.cert.X509Certificate) {
        val allowed = properties.ka.allowedX5cFingerprints()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()
        if (allowed.isEmpty()) {
            return
        }
        val fingerprint = KaCertificateFingerprint.sha256Hex(leaf)
        if (fingerprint !in allowed) {
            throw KeyAttestationValidationException(
                "ka_x5c_untrusted",
                "x5c leaf certificate fingerprint is not in allow-list",
            )
        }
    }
}
