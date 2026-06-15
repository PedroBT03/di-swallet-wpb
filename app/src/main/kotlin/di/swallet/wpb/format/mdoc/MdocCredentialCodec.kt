/**
 * Facade over ISO 18013-5 mdoc encode, decode, and presentation helpers.
 */

package di.swallet.wpb.format.mdoc

import com.authlete.cose.COSEEC2Key
import org.springframework.stereotype.Component

/** Thin codec facade over ISO/IEC 18013-5 CBOR artifacts. */
@Component
class MdocCredentialCodec(
    private val isoRuntime: MdocIsoRuntimeService,
) {
    /** Issues a base64url IssuerSigned artifact for the given document and device key. */
    fun encode(
        document: MdocCredentialDocument,
        devicePublicCoseKey: COSEEC2Key,
    ): String {
        val claimsByNamespace = mapOf(document.namespace to document.claims)
        return isoRuntime.issueIssuerSigned(document.docType, claimsByNamespace, devicePublicCoseKey)
    }

    /** Parses a base64url mdoc artifact into a structured document view. */
    fun decode(raw: String): MdocCredentialDocument? = isoRuntime.decode(raw)

    /** Returns true when the payload decodes as a known mdoc structure. */
    fun isEncodedMdoc(raw: String): Boolean = isoRuntime.isEncodedMdoc(raw)

    /** Verifies issuerAuth COSE signature on an IssuerSigned artifact. */
    fun validateIssuerSigned(raw: String): Boolean = isoRuntime.validateIssuerSigned(raw)

    /** Verifies issuer and device signatures on a DeviceResponse artifact. */
    fun validateDeviceResponse(raw: String): Boolean = isoRuntime.validateDeviceResponse(raw)

    /** Builds a selective DeviceResponse bound to the OpenID4VP handover transcript. */
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

/** Decoded mdoc document with namespace-qualified claim map. */
data class MdocCredentialDocument(
    val docType: String,
    val namespace: String,
    val claims: Map<String, Any?> = emptyMap(),
    val issuer: String? = null,
    val issuedAtEpochSeconds: Long? = null,
)
