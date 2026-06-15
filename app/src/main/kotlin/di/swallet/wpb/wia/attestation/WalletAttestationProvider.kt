/**
 * Wallet Instance Attestation (WIA) issuance port.
 */

package di.swallet.wpb.wia.attestation

import di.swallet.wpb.issuance.domain.WalletInstanceAttestation

/** Issues OAuth client attestation JWTs for OID4VCI authorization. */
interface WalletAttestationProvider {
    /** Creates a WIA and PoP JWT pair bound to the wallet instance key. */
    fun issue(
        holderId: String,
        walletInstanceId: String,
        issuerId: String?,
    ): WalletInstanceAttestation
}
