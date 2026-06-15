/**
 * Verifier access certificate validation against LoTE trust snapshots.
 */

package di.swallet.wpb.presentation.trust

import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.trust.core.TrustSnapshot
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** Result of validating a verifier access certificate. */
sealed interface AccessCertificateValidationResult {
    /** Certificate chain and identity bindings matched the trust snapshot. */
    data object Trusted : AccessCertificateValidationResult

    /** Certificate validation failed with a human-readable reason. */
    data class Rejected(val reason: String) : AccessCertificateValidationResult
}

/**
 * Validates verifier certificate chains and identity bindings from the trust snapshot.
 */
interface AccessCertificateValidationService {
    /**
     * Validates [material] for [requestClientId] against anchors and bindings in [snapshot].
     */
    fun validate(
        requestClientId: String,
        material: VerifierCertificateMaterial,
        snapshot: TrustSnapshot,
    ): AccessCertificateValidationResult
}

/**
 * PKIX-based access certificate validation with LoTE identity binding checks.
 */
@Component
class PkixAccessCertificateValidationService(
    private val certificateChainValidator: CertificateChainValidator,
) : AccessCertificateValidationService {
    /**
     * Validates the certificate chain and checks configured client_id and SAN bindings.
     */
    override fun validate(
        requestClientId: String,
        material: VerifierCertificateMaterial,
        snapshot: TrustSnapshot,
    ): AccessCertificateValidationResult {
        if (snapshot.trustAnchors.isEmpty()) {
            return AccessCertificateValidationResult.Rejected("no trust anchors available")
        }
        val matches = snapshot.entities.values.filter { entity ->
            entity.bindings.any { rule ->
                rule.key.equals("client_id", ignoreCase = true) && rule.value == requestClientId
            }
        }
        if (matches.isEmpty()) {
            return AccessCertificateValidationResult.Rejected("verifier '$requestClientId' is not present in trust snapshot")
        }
        if (matches.size > 1) {
            return AccessCertificateValidationResult.Rejected("ambiguous trust mapping for client_id '$requestClientId'")
        }
        val trustedEntity = matches.first()
        val anchors = snapshot.trustAnchors.map { TrustAnchor(it, null) }.toSet()
        runCatching { certificateChainValidator.validatePkix(material.chain, anchors) }.getOrElse {
            return AccessCertificateValidationResult.Rejected("PKIX validation failed: ${it.message}")
        }

        val leaf = material.leaf
        val leafFingerprint = sha256Hex(leaf.encoded)
        val fingerprintRules = trustedEntity.bindings
            .filter { it.key.equals("cert_sha256", ignoreCase = true) }
            .map { it.value.uppercase() }
            .toSet()
        if (fingerprintRules.isNotEmpty() && leafFingerprint !in fingerprintRules) {
            return AccessCertificateValidationResult.Rejected("identity binding failed (cert_sha256 mismatch)")
        }

        val clientIdRules = trustedEntity.bindings
            .filter { it.key.equals("client_id", ignoreCase = true) }
            .map { it.value }
            .toSet()
        if (clientIdRules.isNotEmpty() && requestClientId !in clientIdRules) {
            return AccessCertificateValidationResult.Rejected("identity binding failed (client_id mismatch)")
        }

        val sanUriRules = trustedEntity.bindings
            .filter { it.key.equals("san_uri", ignoreCase = true) }
            .map { it.value.lowercase() }
            .toSet()
        if (sanUriRules.isNotEmpty()) {
            val certSanUri = extractSanValues(leaf, type = 6)
            if (sanUriRules.intersect(certSanUri).isEmpty()) {
                return AccessCertificateValidationResult.Rejected("identity binding failed (san_uri mismatch)")
            }
        }

        val sanDnsRules = trustedEntity.bindings
            .filter { it.key.equals("san_dns", ignoreCase = true) }
            .map { it.value.lowercase() }
            .toSet()
        if (sanDnsRules.isNotEmpty()) {
            val certSanDns = extractSanValues(leaf, type = 2)
            if (sanDnsRules.intersect(certSanDns).isEmpty()) {
                return AccessCertificateValidationResult.Rejected("identity binding failed (san_dns mismatch)")
            }
        }

        val subjectCnRules = trustedEntity.bindings
            .filter { it.key.equals("subject_cn", ignoreCase = true) }
            .map { it.value.lowercase() }
            .toSet()
        if (subjectCnRules.isNotEmpty()) {
            val subjectCn = extractSubjectCn(leaf)?.lowercase()
            if (subjectCn == null || subjectCn !in subjectCnRules) {
                return AccessCertificateValidationResult.Rejected("identity binding failed (subject_cn mismatch)")
            }
        }

        return AccessCertificateValidationResult.Trusted
    }

    /** Returns the uppercase SHA-256 fingerprint of certificate DER bytes. */
    private fun sha256Hex(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** Extracts SAN values of the given GeneralName type from the certificate. */
    private fun extractSanValues(cert: X509Certificate, type: Int): Set<String> =
        cert.subjectAlternativeNames
            ?.mapNotNull { san -> san.getOrNull(0) to san.getOrNull(1) }
            ?.filter { (sanType, _) -> sanType == type }
            ?.mapNotNull { (_, value) -> value as? String }
            ?.map { it.lowercase() }
            ?.toSet()
            .orEmpty()

    /** Returns the common name from the certificate subject, if present. */
    private fun extractSubjectCn(cert: X509Certificate): String? {
        val principal = X500Principal(cert.subjectX500Principal.name)
        return principal.name
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
