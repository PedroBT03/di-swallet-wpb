package di.swallet.wpb.format.mdoc

import com.authlete.cose.COSEEC2Key
import di.swallet.wpb.config.MdocProperties
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.util.Date

@Component
class MdocIssuerKeyStore(
    private val properties: MdocProperties,
    private val resourceLoader: ResourceLoader,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    @Volatile
    private var cached: IssuerMaterial? = null

    fun material(): IssuerMaterial = cached ?: synchronized(this) {
        cached ?: loadMaterial().also { cached = it }
    }

    private fun loadMaterial(): IssuerMaterial {
        val path = properties.issuerKeyPemPath.trim()
        require(path.isNotBlank()) { "wpb.mdoc.issuer-key-pem-path must be configured" }
        val pem = readPem(path)
            ?: if (properties.autoGenerateIssuerKeyIfMissing && !path.startsWith("classpath:")) {
                logger.warn("event=mdoc.issuer_key.auto_generate path={}", path)
                val generated = generateDevPemBundle()
                writePem(path, generated)
                generated
            } else {
                throw IllegalStateException("mdoc issuer key PEM not found at '$path'")
            }
        val keyPair = MdocCoseKeyMaterial.loadEcKeyPairFromPem(pem)
        val certificate = parseCertificateFromPem(pem)
        return IssuerMaterial(
            keyPair = keyPair,
            certificate = certificate,
            coseKey = MdocCoseKeyMaterial.toCoseEc2Key(
                keyPair.private as ECPrivateKey,
                keyPair.public as ECPublicKey,
            ),
        )
    }

    private fun readPem(path: String): String? = runCatching {
        when {
            path.startsWith("classpath:") -> {
                val resource = resourceLoader.getResource(path)
                if (!resource.exists()) return null
                resource.inputStream.bufferedReader().use { it.readText() }
            }
            else -> {
                val file = File(path)
                if (!file.exists()) return null
                file.readText()
            }
        }
    }.getOrNull()

    private fun writePem(path: String, pem: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        Files.writeString(file.toPath(), pem)
    }

    private fun parseCertificateFromPem(pem: String): X509Certificate {
        val certB64 = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
            .find(pem)?.groupValues?.get(1)?.replace("\\s".toRegex(), "")
            ?: throw IllegalArgumentException("PEM bundle must contain a certificate")
        val der = java.util.Base64.getDecoder().decode(certB64)
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun generateDevPemBundle(): String {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val cert = selfSignedCertificate(keyPair, "CN=WPB mdoc simulator issuer")
        val privateDer = java.util.Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val certDer = java.util.Base64.getEncoder().encodeToString(cert.encoded)
        return """
            -----BEGIN EC PRIVATE KEY-----
            ${privateDer.chunked(64).joinToString("\n")}
            -----END EC PRIVATE KEY-----
            -----BEGIN CERTIFICATE-----
            ${certDer.chunked(64).joinToString("\n")}
            -----END CERTIFICATE-----
        """.trimIndent() + "\n"
    }

    private fun selfSignedCertificate(keyPair: KeyPair, subjectDn: String): X509Certificate {
        val subject = X500Name(subjectDn)
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            Date(now.time + 365L * 24 * 60 * 60 * 1000),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    data class IssuerMaterial(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val coseKey: COSEEC2Key,
    )
}
