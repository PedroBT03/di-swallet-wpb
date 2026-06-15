/**
 * Derives the relying-party DNS name used in DPA report templates and substantiation.
 */

package di.swallet.wpb.dpareport

import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.trust.VerifierCertificateExtractor
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import org.springframework.stereotype.Component
import java.security.cert.X509Certificate

/** Provenance of the resolved relying-party DNS name. */
enum class DnsNameSource {
    CERTIFICATE_SAN,
    CLIENT_ID,
    RP_IDENTIFIER,
    RP_NAME,
}

/** DNS name chosen for DPA reporting together with its resolution source. */
data class ResolvedRpDnsName(
    val value: String,
    val source: DnsNameSource,
)

/** Resolves an RP DNS name from presentation logs, client IDs, or verifier certificates. */
@Component
class RpDnsNameResolver(
    private val certificateExtractor: VerifierCertificateExtractor,
) {
    /** Resolves the RP DNS name from a live presentation context. */
    fun fromContext(context: PresentationContext): String? =
        resolveFromPresentation(
            rpDnsName = extractDnsFromContext(context),
            rpIdentifier = context.registryRecord?.identifier ?: context.verifierIdentity?.clientId,
            rpName = context.registryRecord?.tradeName ?: context.verifierIdentity?.displayName,
        )?.value

    /** Resolves the RP DNS name stored on a TS10 presentation transaction. */
    fun fromPresentation(presentation: Ts10Presentation): ResolvedRpDnsName? =
        resolveFromPresentation(
            rpDnsName = presentation.rpDnsName,
            rpIdentifier = presentation.interactingPartyIdentifier?.identifier,
            rpName = presentation.interactingPartyName,
        )

    /** Picks the best DNS label from stored SAN, client ID, identifier, or display name. */
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

    /** Extracts a DNS SAN from the verifier certificate or x509_san_dns client ID. */
    private fun extractDnsFromContext(context: PresentationContext): String? {
        val request = context.authorizationRequest ?: return null
        extractDnsFromClientId(request.clientId)?.let { return it }
        val material = certificateExtractor.extract(request) ?: return null
        return extractFirstDnsSan(material.leaf)
    }

    /** Parses an x509_san_dns client identifier into a DNS host name. */
    private fun extractDnsFromClientId(clientId: String): String? {
        val lower = clientId.lowercase()
        if (!lower.startsWith("x509_san_dns:")) return null
        return clientId.substringAfter(':').trim().takeIf { it.isNotBlank() }
    }

    /** Returns the first DNS subject alternative name from an X.509 certificate. */
    private fun extractFirstDnsSan(cert: X509Certificate): String? =
        cert.subjectAlternativeNames
            ?.mapNotNull { san -> san.getOrNull(0) to san.getOrNull(1) }
            ?.firstOrNull { (type, _) -> type == 2 }
            ?.second as? String

    /** Heuristic check for hostname-like identifiers. */
    private fun looksLikeDns(value: String): Boolean =
        value.contains('.') && !value.contains(' ')
}
