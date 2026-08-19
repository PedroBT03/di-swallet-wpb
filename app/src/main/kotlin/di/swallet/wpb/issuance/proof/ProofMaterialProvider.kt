/**
 * Proof-of-possession key material supplied to the OID4VCI adapter.
 */

package di.swallet.wpb.issuance.proof

import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import java.security.interfaces.ECPublicKey

/**
 * Wallet-side material required to satisfy issuer proof-of-possession.
 *
 * Only JWT proofs (algorithm `ES256`) are supported. The
 * wallet binds the credential to a hardware-backed key managed by the
 * HSM. `kid` corresponds to the WSCA key identifier and is forwarded
 * to the adapter so it can build the JWT header.
 */
data class ProofMaterial(
    val keyId: String,
    val publicKey: ECPublicKey,
    val algorithm: String = "ES256",
)

/** Supplies ES256 proof keys for a holder during issuance. */
interface ProofMaterialProvider {
    /** Returns stable or freshly provisioned proof material for the holder. */
    fun provide(
        holderId: String,
        metadata: ResolvedIssuerMetadata?,
    ): ProofMaterial
}
