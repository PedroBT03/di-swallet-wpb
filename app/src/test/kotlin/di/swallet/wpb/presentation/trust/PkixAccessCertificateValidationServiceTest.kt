/**
 * Tests pkix access certificate validation service.
 */

package di.swallet.wpb.presentation.trust

import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.trust.core.TrustBindingRule
import di.swallet.wpb.trust.core.TrustSnapshot
import di.swallet.wpb.trust.core.TrustedEntity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date

class PkixAccessCertificateValidationServiceTest {
    private val service = PkixAccessCertificateValidationService(CertificateChainValidator(DefaultResourceLoader()))

    /**
     * Trust snapshot binds client_id and cert_sha256 to a valid leaf chain.
     * validate returns Trusted for the matching verifier client.
     */
    @Test
    fun `accepts trusted verifier when fingerprint and chain match`() {
        val cert = selfSignedCert(notBefore = Instant.now().minusSeconds(60), notAfter = Instant.now().plusSeconds(3600))
        val snapshot = TrustSnapshot(
            trustAnchors = listOf(cert),
            entities = mapOf(
                "verifier-demo-client" to TrustedEntity(
                    entityId = "verifier-demo-client",
                    bindings = setOf(
                        TrustBindingRule("client_id", "verifier-demo-client"),
                        TrustBindingRule("cert_sha256", fingerprint(cert)),
                    ),
                ),
            ),
            source = "test",
            loadedAt = Instant.now(),
        )
        val material = VerifierCertificateMaterial(chain = listOf(cert), leaf = cert)
        val result = service.validate("verifier-demo-client", material, snapshot)
        assertTrue(result is AccessCertificateValidationResult.Trusted)
    }

    /**
     * Presenter certificate is outside its validity window.
     * validate returns Rejected with a reason mentioning validity.
     */
    @Test
    fun `rejects expired certificate`() {
        val expired = selfSignedCert(notBefore = Instant.now().minusSeconds(7200), notAfter = Instant.now().minusSeconds(3600))
        val snapshot = TrustSnapshot(
            trustAnchors = listOf(expired),
            entities = mapOf(
                "verifier-demo-client" to TrustedEntity(
                    entityId = "verifier-demo-client",
                    bindings = setOf(TrustBindingRule("client_id", "verifier-demo-client")),
                ),
            ),
            source = "test",
            loadedAt = Instant.now(),
        )
        val result = service.validate("verifier-demo-client", VerifierCertificateMaterial(listOf(expired), expired), snapshot)
        assertTrue(result is AccessCertificateValidationResult.Rejected)
        assertTrue((result as AccessCertificateValidationResult.Rejected).reason.contains("validity", ignoreCase = true))
    }

    /**
     * Snapshot has no trust anchors although the client_id is registered.
     * validate returns Rejected citing missing trust anchors.
     */
    @Test
    fun `rejects untrusted chain when no anchors exist`() {
        val cert = selfSignedCert(notBefore = Instant.now().minusSeconds(60), notAfter = Instant.now().plusSeconds(3600))
        val snapshot = TrustSnapshot(
            trustAnchors = emptyList(),
            entities = mapOf(
                "verifier-demo-client" to TrustedEntity(
                    entityId = "verifier-demo-client",
                    bindings = setOf(TrustBindingRule("client_id", "verifier-demo-client")),
                ),
            ),
            source = "test",
            loadedAt = Instant.now(),
        )
        val result = service.validate("verifier-demo-client", VerifierCertificateMaterial(listOf(cert), cert), snapshot)
        assertTrue(result is AccessCertificateValidationResult.Rejected)
        assertTrue((result as AccessCertificateValidationResult.Rejected).reason.contains("trust anchors", ignoreCase = true))
    }

    /**
     * Snapshot expects cert_sha256 DEADBEEF but the presented leaf differs.
     * validate returns Rejected mentioning cert_sha256 mismatch.
     */
    @Test
    fun `rejects identity mismatch on fingerprint policy`() {
        val cert = selfSignedCert(notBefore = Instant.now().minusSeconds(60), notAfter = Instant.now().plusSeconds(3600))
        val snapshot = TrustSnapshot(
            trustAnchors = listOf(cert),
            entities = mapOf(
                "verifier-demo-client" to TrustedEntity(
                    entityId = "verifier-demo-client",
                    bindings = setOf(
                        TrustBindingRule("client_id", "verifier-demo-client"),
                        TrustBindingRule("cert_sha256", "DEADBEEF"),
                    ),
                ),
            ),
            source = "test",
            loadedAt = Instant.now(),
        )
        val result = service.validate("verifier-demo-client", VerifierCertificateMaterial(listOf(cert), cert), snapshot)
        assertTrue(result is AccessCertificateValidationResult.Rejected)
        assertTrue((result as AccessCertificateValidationResult.Rejected).reason.contains("cert_sha256", ignoreCase = true))
    }

    /** Issues a self-signed EC certificate valid between the given notBefore and notAfter instants. */
    /** Issues a short-lived self-signed EC certificate bounded by the supplied notBefore and notAfter instants. */
    private fun selfSignedCert(notBefore: Instant, notAfter: Instant): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val subject = X500Name("CN=Verifier")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.nanoTime()),
            Date.from(notBefore),
            Date.from(notAfter),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** Uppercase hex SHA-256 fingerprint of the certificate DER encoding for trust binding checks. */
    /** Computes the uppercase SHA-256 hex fingerprint of the certificate DER encoding. */
    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }
}
