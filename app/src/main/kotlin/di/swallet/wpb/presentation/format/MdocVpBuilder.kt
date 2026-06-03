package di.swallet.wpb.presentation.format

import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.service.CredentialBindingValidationService
import di.swallet.wpb.domain.CredentialBindingFormat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MdocVpBuilder(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val mdocCredentialCodec: MdocCredentialCodec,
    private val mdocDocTypeRegistry: MdocDocTypeRegistry,
    private val credentialBindingValidationService: CredentialBindingValidationService? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun build(
        selected: SelectedCredential,
        verifierAudience: String,
        verifierNonce: String,
    ): MdocVpResult {
        val credentialId = selected.credentialId
            ?: return demoFallback(selected, verifierAudience, verifierNonce)
        val credential = walletCredentialRepository.findById(credentialId)
            .orElseThrow { IllegalStateException("Selected credential $credentialId not found") }
        credentialBindingValidationService?.requireBinding(credentialId, CredentialBindingFormat.MDOC)
        val decoded = mdocCredentialCodec.decode(credential.encodedData)
            ?: throw IllegalStateException("Selected credential $credentialId is not a decodable mdoc payload")
        val effectiveDocType = if (decoded.docType == "unknown") credential.credentialType else decoded.docType
        val definition = mdocDocTypeRegistry.resolve(effectiveDocType)
            ?: throw IllegalStateException("Unsupported mdoc docType '${decoded.docType}'")

        val filteredClaims = filterClaims(decoded.claims, definition.claimMapping, selected.requestedClaims)
        val namespaceClaims = toNamespaceClaims(filteredClaims)
        val encodedPresentation = mdocCredentialCodec.buildDeviceResponse(
            originalIssuedPayload = credential.encodedData,
            docType = effectiveDocType,
            namespaceClaims = namespaceClaims,
            requestedClaims = selected.requestedClaims,
            audience = verifierAudience,
            nonce = verifierNonce,
            holderKeyAlias = runCatching { credential.walletKey?.keyAlias }.getOrNull(),
        )

        logger.info(
            "mdoc.vp.built credential={} docType={} disclosed={} aud={}",
            credentialId,
            effectiveDocType,
            filteredClaims.size,
            verifierAudience,
        )
        return MdocVpResult(
            presentation = encodedPresentation,
            disclosedClaims = filteredClaims.size,
            isDemo = false,
        )
    }

    private fun filterClaims(
        decodedClaims: Map<String, Any?>,
        mapping: Map<String, String>,
        requestedClaims: List<String>,
    ): Map<String, Any?> {
        if (requestedClaims.isEmpty()) return decodedClaims
        val canonicalRequested = requestedClaims.map { claim ->
            mapping[claim] ?: mapping[claim.lowercase()] ?: claim
        }.toSet()
        return decodedClaims.filterKeys { key ->
            val claimName = key.substringAfterLast('.')
            claimName in canonicalRequested || key in canonicalRequested
        }
    }

    private fun toNamespaceClaims(flatClaims: Map<String, Any?>): Map<String, Map<String, Any?>> {
        if (flatClaims.isEmpty()) return emptyMap()
        val grouped = linkedMapOf<String, MutableMap<String, Any?>>()
        flatClaims.forEach { (key, value) ->
            val namespace = key.substringBeforeLast('.', missingDelimiterValue = "org.iso.18013.5.1")
            val claim = key.substringAfterLast('.')
            grouped.getOrPut(namespace) { linkedMapOf() }[claim] = value
        }
        return grouped
    }

    private fun demoFallback(
        selected: SelectedCredential,
        verifierAudience: String,
        verifierNonce: String,
    ): MdocVpResult {
        val payload = mdocCredentialCodec.encode(
            MdocCredentialDocument(
                docType = "demo.mdoc",
                namespace = "demo.mdoc",
                claims = mapOf(
                    "candidate" to selected.candidateId,
                    "aud" to verifierAudience,
                    "nonce" to verifierNonce,
                ),
            ),
        )
        logger.warn("mdoc.vp.built using demo fallback for synthetic candidate {}", selected.candidateId)
        return MdocVpResult(presentation = payload, disclosedClaims = 0, isDemo = true)
    }
}

data class MdocVpResult(
    val presentation: String,
    val disclosedClaims: Int,
    val isDemo: Boolean,
)
