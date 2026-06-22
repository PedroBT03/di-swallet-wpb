/**
 * Resolves the ISO docType used for mdoc matching and presentation from stored wallet metadata.
 */

package di.swallet.wpb.format.mdoc

import di.swallet.wpb.domain.CredentialTypeLabels
import org.springframework.stereotype.Component

/**
 * IssuerSigned decode does not surface MSO docType; wallet rows may store a short label (e.g. MDL).
 */
@Component
class MdocEffectiveDocTypeResolver(
    private val registry: MdocDocTypeRegistry,
) {
    /** Returns the docType to use for DCQL matching and DeviceResponse assembly. */
    fun resolve(credentialType: String, decoded: MdocCredentialDocument): String {
        if (decoded.docType != "unknown") {
            return decoded.docType
        }
        registry.infer(
            configurationId = credentialType,
            docTypeHint = null,
            vctHint = null,
        )?.docType?.let { return it }
        return when {
            CredentialTypeLabels.isMdlType(credentialType) -> "org.iso.18013.5.1.mDL"
            CredentialTypeLabels.isPidType(credentialType) -> "eu.europa.ec.eudi.pid.1"
            else -> credentialType
        }
    }
}
