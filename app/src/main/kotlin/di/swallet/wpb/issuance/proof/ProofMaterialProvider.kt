package di.swallet.wpb.issuance.proof

import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import java.security.interfaces.ECPublicKey

/**
 * Wallet-side material required to satisfy issuer proof-of-possession.
 *
 * The Phase 2 MVP only supports JWT proofs (algorithm `ES256`). The
 * wallet binds the credential to a hardware-backed key managed by the
 * HSM. `kid` corresponds to the WSCA key identifier and is forwarded
 * to the adapter so it can build the JWT header.
 */
data class ProofMaterial(
    val keyId: String,
    val publicKey: ECPublicKey,
    val algorithm: String = "ES256",
)

interface ProofMaterialProvider {
    fun provide(
        holderId: String,
        metadata: ResolvedIssuerMetadata?,
    ): ProofMaterial
}
