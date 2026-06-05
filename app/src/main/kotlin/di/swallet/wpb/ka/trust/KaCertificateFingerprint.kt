package di.swallet.wpb.ka.trust

import java.security.MessageDigest
import java.security.cert.X509Certificate

object KaCertificateFingerprint {
    fun sha256Hex(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }
}
