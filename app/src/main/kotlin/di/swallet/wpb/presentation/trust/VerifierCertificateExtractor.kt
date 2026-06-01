package di.swallet.wpb.presentation.trust

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

data class VerifierCertificateMaterial(
    val chain: List<X509Certificate>,
    val leaf: X509Certificate,
)

interface VerifierCertificateExtractor {
    fun extract(request: ResolvedAuthorizationRequest): VerifierCertificateMaterial?
}

@Component
class DefaultVerifierCertificateExtractor : VerifierCertificateExtractor {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val certFactory = CertificateFactory.getInstance("X.509")

    override fun extract(request: ResolvedAuthorizationRequest): VerifierCertificateMaterial? {
        val raw = request.verifierInfoJson?.trim().orEmpty()
        if (raw.isBlank()) return null
        val root = runCatching { mapper.readTree(raw) }.getOrNull() ?: return null
        val chain = extractX5c(root).ifEmpty { extractPemChain(root) }
        if (chain.isEmpty()) return null
        return VerifierCertificateMaterial(chain = chain, leaf = chain.first())
    }

    private fun extractX5c(root: JsonNode): List<X509Certificate> {
        val candidates = sequenceOf(
            root["x5c"],
            root["certificateChain"],
            root["access_certificate"]?.get("x5c"),
            root["accessCertificate"]?.get("x5c"),
        )
        val x5cNode = candidates.firstOrNull { it != null && it.isArray } ?: return emptyList()
        return x5cNode.mapNotNull { node ->
            val derB64 = node.asText().trim()
            if (derB64.isBlank()) return@mapNotNull null
            val der = runCatching { Base64.getDecoder().decode(derB64) }.getOrNull() ?: return@mapNotNull null
            certFactory.generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
        }
    }

    private fun extractPemChain(root: JsonNode): List<X509Certificate> {
        val pemCandidates = sequenceOf(
            root["certificatePem"]?.asText(),
            root["certificate_pem"]?.asText(),
            root["access_certificate"]?.get("certificatePem")?.asText(),
            root["accessCertificate"]?.get("certificatePem")?.asText(),
        ).filterNotNull().toList()
        if (pemCandidates.isEmpty()) return emptyList()
        return pemCandidates.flatMap { pemBundle ->
            val regex = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
            regex.findAll(pemBundle).mapNotNull { match ->
                val b64 = match.groupValues[1].replace("\\s".toRegex(), "")
                val der = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return@mapNotNull null
                certFactory.generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
            }.toList()
        }
    }
}
