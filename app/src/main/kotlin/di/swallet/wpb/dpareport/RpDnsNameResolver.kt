package di.swallet.wpb.dpareport

import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.trust.VerifierCertificateExtractor
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import org.springframework.stereotype.Component
import java.security.cert.X509Certificate

enum class DnsNameSource {
    CERTIFICATE_SAN,
    CLIENT_ID,
    RP_IDENTIFIER,
    RP_NAME,
}

data class ResolvedRpDnsName(
    val value: String,
    val source: DnsNameSource,
)

@Component
class RpDnsNameResolver(
    private val certificateExtractor: VerifierCertificateExtractor,
) {
    fun fromContext(context: PresentationContext): String? =
        resolveFromPresentation(
            rpDnsName = extractDnsFromContext(context),
            rpIdentifier = context.registryRecord?.identifier ?: context.verifierIdentity?.clientId,
            rpName = context.registryRecord?.tradeName ?: context.verifierIdentity?.displayName,
        )?.value

    fun fromPresentation(presentation: Ts10Presentation): ResolvedRpDnsName? =
        resolveFromPresentation(
            rpDnsName = presentation.rpDnsName,
            rpIdentifier = presentation.interactingPartyIdentifier?.identifier,
            rpName = presentation.interactingPartyName,
        )

    private fun resolveFromPresentation(
        rpDnsName: String?,
        rpIdentifier: String?,
        rpName: String?,
    ): ResolvedRpDnsName? {
        rpDnsName?.takeIf { it.isNotBlank() }?.let {
            return ResolvedRpDnsName(it, DnsNameSource.CERTIFICATE_SAN)
        }
        rpIdentifier?.takeIf { it.isNotBlank() }?.let { id ->
            extractDnsFromClientId(id)?.let { return ResolvedRpDnsName(it, DnsNameSource.CLIENT_ID) }
            if (looksLikeDns(id)) return ResolvedRpDnsName(id, DnsNameSource.RP_IDENTIFIER)
            return ResolvedRpDnsName(id, DnsNameSource.RP_IDENTIFIER)
        }
        rpName?.takeIf { it.isNotBlank() }?.let {
            return ResolvedRpDnsName(it, DnsNameSource.RP_NAME)
        }
        return null
    }

    private fun extractDnsFromContext(context: PresentationContext): String? {
        val request = context.authorizationRequest ?: return null
        extractDnsFromClientId(request.clientId)?.let { return it }
        val material = certificateExtractor.extract(request) ?: return null
        return extractFirstDnsSan(material.leaf)
    }

    private fun extractDnsFromClientId(clientId: String): String? {
        val lower = clientId.lowercase()
        if (!lower.startsWith("x509_san_dns:")) return null
        return clientId.substringAfter(':').trim().takeIf { it.isNotBlank() }
    }

    private fun extractFirstDnsSan(cert: X509Certificate): String? =
        cert.subjectAlternativeNames
            ?.mapNotNull { san -> san.getOrNull(0) to san.getOrNull(1) }
            ?.firstOrNull { (type, _) -> type == 2 }
            ?.second as? String

    private fun looksLikeDns(value: String): Boolean =
        value.contains('.') && !value.contains(' ')
}
