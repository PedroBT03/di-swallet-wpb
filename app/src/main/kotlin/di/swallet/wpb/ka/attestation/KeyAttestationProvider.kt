/**
 * Key Attestation (KA) issuance port for device-bound credentials.
 */

package di.swallet.wpb.ka.attestation

import di.swallet.wpb.issuance.domain.KeyAttestation
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import java.security.interfaces.ECPublicKey

/** Issues key attestation JWTs binding proof keys to wallet key storage claims. */
interface KeyAttestationProvider {
    /** Creates a KA JWT attesting the proof public key for the requested configuration. */
    fun issue(
        holderId: String,
        issuerId: String?,
        metadata: ResolvedIssuerMetadata,
        configuration: CredentialConfigurationDescriptor,
        proofPublicKey: ECPublicKey,
        proofKeyId: String,
    ): KeyAttestation

    /**
     * Creates a KA JWT for wallet provisioning, attesting the holder's freshly generated key
     * before any issuer interaction. Together with the WIA this forms the WUA emitted at init.
     */
    fun issueForProvisioning(
        holderId: String,
        keyAlias: String,
        proofPublicKey: ECPublicKey,
    ): KeyAttestation
}
