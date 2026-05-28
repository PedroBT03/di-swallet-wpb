package di.swallet.wpb.ka.trust

import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64

@Component
class CertificateChainValidator(
    private val resourceLoader: ResourceLoader,
) {
    fun parseDerBase64Chain(x5c: List<String>): List<X509Certificate> {
        val certFactory = CertificateFactory.getInstance("X.509")
        return x5c.map { raw ->
            val clean = raw
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s".toRegex(), "")
            val bytes = Base64.getDecoder().decode(clean)
            certFactory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }
    }

    fun validatePkix(chain: List<X509Certificate>, trustAnchorPaths: List<String>) {
        require(chain.isNotEmpty()) { "x5c chain is required for PKIX validation" }
        val anchors = loadTrustAnchors(trustAnchorPaths)
        require(anchors.isNotEmpty()) { "strict trust mode requires configured trust anchors" }

        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(chain)
        val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(certPath, params)
    }

    private fun loadTrustAnchors(paths: List<String>): Set<TrustAnchor> {
        val certFactory = CertificateFactory.getInstance("X.509")
        return paths.flatMap { path ->
            val pem = readPath(path)
            pemToCertificates(pem, certFactory)
        }.map { TrustAnchor(it, null) }.toSet()
    }

    private fun readPath(path: String): String {
        return when {
            path.startsWith("classpath:") -> {
                val resource = resourceLoader.getResource(path)
                resource.inputStream.bufferedReader().use { it.readText() }
            }
            else -> File(path).readText()
        }
    }

    private fun pemToCertificates(content: String, certFactory: CertificateFactory): List<X509Certificate> {
        val pemRegex = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
        val pems = pemRegex.findAll(content).map { it.groupValues[1] }.toList()
        val chunks = if (pems.isNotEmpty()) pems else listOf(content)
        return chunks.map { raw ->
            val clean = raw.replace("\\s".toRegex(), "")
            val bytes = Base64.getDecoder().decode(clean)
            certFactory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }
    }
}
