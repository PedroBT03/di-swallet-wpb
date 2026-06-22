/**
 * Builds mdoc device responses for OpenID4VP presentation.
 */

package di.swallet.wpb.presentation.format

import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocEffectiveDocTypeResolver
import di.swallet.wpb.format.mdoc.MdocOpenId4VpHandover
import di.swallet.wpb.format.mdoc.MdocCoseKeyMaterial
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.service.CredentialBindingValidationService
import di.swallet.wpb.domain.CredentialBindingFormat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Encodes an mdoc device response for one selected credential and OpenID4VP handover data.
 */
@Service
class MdocVpBuilder(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val mdocCredentialCodec: MdocCredentialCodec,
    private val mdocDocTypeRegistry: MdocDocTypeRegistry,
    private val mdocEffectiveDocTypeResolver: MdocEffectiveDocTypeResolver,
    private val credentialBindingValidationService: CredentialBindingValidationService? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Builds an mdoc presentation for [selected], filtering claims to those requested by the verifier.
     */
    fun build(
        selected: SelectedCredential,
        handover: MdocOpenId4VpHandover,
    ): MdocVpResult {
        val credentialId = selected.credentialId
            ?: return demoFallback(selected, handover)
        val credential = walletCredentialRepository.findById(credentialId)
            .orElseThrow { IllegalStateException("Selected credential $credentialId not found") }
        credentialBindingValidationService?.requireBinding(credentialId, CredentialBindingFormat.MDOC)
        val decoded = mdocCredentialCodec.decode(credential.encodedData)
            ?: throw IllegalStateException("Selected credential $credentialId is not a decodable mdoc payload")
        val effectiveDocType = mdocEffectiveDocTypeResolver.resolve(credential.credentialType, decoded)
        val definition = mdocDocTypeRegistry.resolve(effectiveDocType)
            ?: throw IllegalStateException("Unsupported mdoc docType '${decoded.docType}'")

        val filteredClaims = filterClaims(decoded.claims, definition.claimMapping, selected.requestedClaims)
        val namespaceClaims = toNamespaceClaims(filteredClaims)
        val encodedPresentation = mdocCredentialCodec.buildDeviceResponse(
            originalIssuedPayload = credential.encodedData,
            docType = effectiveDocType,
            namespaceClaims = namespaceClaims,
            requestedClaims = selected.requestedClaims,
            handover = handover,
            holderKeyAlias = runCatching { credential.walletKey?.keyAlias }.getOrNull(),
        )

        logger.info(
            "mdoc.vp.built credential={} docType={} disclosed={} aud={}",
            credentialId,
            effectiveDocType,
            filteredClaims.size,
            handover.audience,
        )
        return MdocVpResult(
            presentation = encodedPresentation,
            disclosedClaims = filteredClaims.size,
            isDemo = false,
        )
    }

    /** Keeps only claims requested by the verifier, using doc-type claim mapping when available. */
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

    /** Groups flat claim keys into ISO mdoc namespace maps. */
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

    /** Builds a synthetic demo presentation when no stored credential id is available. */
    private fun demoFallback(
        selected: SelectedCredential,
        handover: MdocOpenId4VpHandover,
    ): MdocVpResult {
        val ephemeral = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val deviceKey = MdocCoseKeyMaterial.toCoseEc2PublicKey(ephemeral.public as java.security.interfaces.ECPublicKey)
        val payload = mdocCredentialCodec.encode(
            MdocCredentialDocument(
                docType = "demo.mdoc",
                namespace = "demo.mdoc",
                claims = mapOf(
                    "candidate" to selected.candidateId,
                    "aud" to handover.audience,
                    "nonce" to handover.nonce,
                ),
            ),
            deviceKey,
        )
        logger.warn("mdoc.vp.built using demo fallback for synthetic candidate {}", selected.candidateId)
        return MdocVpResult(presentation = payload, disclosedClaims = 0, isDemo = true)
    }
}

/** Result of encoding one mdoc presentation. */
data class MdocVpResult(
    val presentation: String,
    val disclosedClaims: Int,
    val isDemo: Boolean,
)
