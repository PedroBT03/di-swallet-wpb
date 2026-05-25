package di.swallet.wpb.wia.attestation

import di.swallet.wpb.issuance.domain.WalletInstanceAttestation

interface WalletAttestationProvider {
    fun issue(
        holderId: String,
        walletInstanceId: String,
        issuerId: String?,
    ): WalletInstanceAttestation
}

