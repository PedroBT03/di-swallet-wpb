/**
 * SHA-256 fingerprint helper for key attestation x5c allow-lists.
 */

package di.swallet.wpb.ka.trust

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Computes uppercase hex SHA-256 fingerprints for X.509 certificates. */
object KaCertificateFingerprint {
    /** Returns the SHA-256 digest of the certificate DER encoding as uppercase hex. */
    fun sha256Hex(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }
}
