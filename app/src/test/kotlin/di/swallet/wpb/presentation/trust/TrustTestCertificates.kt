/**
 * Reusable test certificate chains for presentation trust tests.
 */

package di.swallet.wpb.presentation.trust

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date

object TrustTestCertificates {
    data class CertChain(val root: X509Certificate, val leaf: X509Certificate)

    /** Issues a root CA and leaf verifier certificate chain with the given DNS SAN on the leaf. */
    /** Issues a two-level EC certificate chain with DNS SAN on the leaf for trust binding tests. */
    fun issueChain(clientIdDns: String = "verifier.example"): CertChain {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val rootKeys = generator.generateKeyPair()
        val leafKeys = generator.generateKeyPair()
        val root = selfSignedCa(rootKeys, "CN=LoTE Root CA")
        val leaf = issuedLeaf(root, rootKeys, leafKeys, "CN=Verifier Leaf", clientIdDns)
        return CertChain(root = root, leaf = leaf)
    }

    /** Uppercase hex SHA-256 fingerprint of a certificate for cert_sha256 trust bindings. */
    /** Returns the uppercase SHA-256 hex digest of the certificate DER bytes. */
    fun sha256Hex(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }

    /** Formats an X509Certificate as a PEM block for embedding in trust documents or JSON. */
    /** Formats the certificate as a PEM block with base64-encoded DER on a single line. */
    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n${Base64.getEncoder().encodeToString(cert.encoded)}\n-----END CERTIFICATE-----\n"

    /** Escapes a raw string for safe inclusion as a JSON string literal in test fixtures. */
    /** Escapes a multi-line PEM string for safe embedding inside JSON string literals. */
    fun jsonString(raw: String): String =
        "\"" + raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

    /** Creates a self-signed CA certificate with basicConstraints CA=true for test chains. */
    /** Creates a self-signed CA certificate with basicConstraints set to CA true. */
    private fun selfSignedCa(keys: KeyPair, subjectDn: String): X509Certificate {
        val subject = X500Name(subjectDn)
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.nanoTime()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(86400)),
            subject,
            keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** Issues a leaf certificate signed by the CA with a DNS subjectAlternativeName for verifier binding. */
    /** Issues a leaf certificate signed by the CA with a DNS subjectAlternativeName entry. */
    private fun issuedLeaf(
        issuerCert: X509Certificate,
        issuerKeys: KeyPair,
        leafKeys: KeyPair,
        subjectDn: String,
        dnsSan: String,
    ): X509Certificate {
        val issuer = X500Name(issuerCert.subjectX500Principal.name)
        val subject = X500Name(subjectDn)
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.nanoTime() + 1),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            subject,
            leafKeys.public,
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, dnsSan)),
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
