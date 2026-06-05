package di.swallet.wpb.format.mdoc

import com.authlete.cose.COSEEC2Key
import org.springframework.stereotype.Component

/**
 * Codec facade over ISO/IEC 18013-5 CBOR artifacts.
 */
@Component
class MdocCredentialCodec(
    private val isoRuntime: MdocIsoRuntimeService,
) {
    fun encode(
        document: MdocCredentialDocument,
        devicePublicCoseKey: COSEEC2Key,
    ): String {
        val claimsByNamespace = mapOf(document.namespace to document.claims)
        return isoRuntime.issueIssuerSigned(document.docType, claimsByNamespace, devicePublicCoseKey)
    }

    fun decode(raw: String): MdocCredentialDocument? = isoRuntime.decode(raw)

    fun isEncodedMdoc(raw: String): Boolean = isoRuntime.isEncodedMdoc(raw)

    fun validateIssuerSigned(raw: String): Boolean = isoRuntime.validateIssuerSigned(raw)

    fun validateDeviceResponse(raw: String): Boolean = isoRuntime.validateDeviceResponse(raw)

    fun buildDeviceResponse(
        originalIssuedPayload: String?,
        docType: String,
        namespaceClaims: Map<String, Map<String, Any?>>,
        requestedClaims: List<String>,
        handover: MdocOpenId4VpHandover,
        holderKeyAlias: String? = null,
    ): String = isoRuntime.buildDeviceResponse(
        originalIssuedPayload = originalIssuedPayload,
        docType = docType,
        namespaceClaims = namespaceClaims,
        requestedClaims = requestedClaims,
        handover = handover,
        holderKeyAlias = holderKeyAlias,
    )
}

data class MdocCredentialDocument(
    val docType: String,
    val namespace: String,
    val claims: Map<String, Any?> = emptyMap(),
    val issuer: String? = null,
    val issuedAtEpochSeconds: Long? = null,
)
