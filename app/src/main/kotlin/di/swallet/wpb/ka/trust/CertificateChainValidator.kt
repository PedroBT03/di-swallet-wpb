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
        validatePkix(chain, anchors, "strict trust mode requires configured trust anchors")
    }

    fun validatePkix(
        chain: List<X509Certificate>,
        anchors: Set<TrustAnchor>,
        emptyAnchorError: String = "trust anchors are required for PKIX validation",
    ) {
        require(chain.isNotEmpty()) { "x5c chain is required for PKIX validation" }
        require(anchors.isNotEmpty()) { emptyAnchorError }
        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(chain)
        val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(certPath, params)
    }

    fun loadTrustAnchors(paths: List<String>): Set<TrustAnchor> {
        return paths.flatMap { path ->
            val pem = readPath(path)
            parsePemCertificates(pem)
        }.map { TrustAnchor(it, null) }.toSet()
    }

    fun readPath(path: String): String {
        return when {
            path.startsWith("classpath:") -> {
                val resource = resourceLoader.getResource(path)
                resource.inputStream.bufferedReader().use { it.readText() }
            }
            else -> File(path).readText()
        }
    }

    fun parsePemCertificates(content: String): List<X509Certificate> {
        val certFactory = CertificateFactory.getInstance("X.509")
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
