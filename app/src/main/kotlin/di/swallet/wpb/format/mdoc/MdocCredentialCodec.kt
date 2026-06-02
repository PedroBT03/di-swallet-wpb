package di.swallet.wpb.format.mdoc

import org.springframework.stereotype.Component

/**
 * Codec facade over ISO/IEC 18013-5 CBOR artifacts.
 *
 * Runtime encoding now emits real mdoc structures (IssuerSigned / DeviceResponse)
 * serialized as base64url(CBOR) without proprietary wrappers.
 */
@Component
class MdocCredentialCodec(
    private val isoRuntime: MdocIsoRuntimeService,
) {
    fun encode(document: MdocCredentialDocument): String {
        val namespace = document.namespace
        val claimsByNamespace = mapOf(namespace to document.claims)
        return isoRuntime.issueIssuerSigned(document.docType, claimsByNamespace)
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
        audience: String,
        nonce: String,
    ): String = isoRuntime.buildDeviceResponse(
        originalIssuedPayload = originalIssuedPayload,
        docType = docType,
        namespaceClaims = namespaceClaims,
        requestedClaims = requestedClaims,
        audience = audience,
        nonce = nonce,
    )
}

data class MdocCredentialDocument(
    val docType: String,
    val namespace: String,
    val claims: Map<String, Any?> = emptyMap(),
    val issuer: String? = null,
    val issuedAtEpochSeconds: Long? = null,
)
