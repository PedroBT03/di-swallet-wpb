package di.swallet.wpb.ka.attestation

import di.swallet.wpb.issuance.domain.KeyAttestation
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import java.security.interfaces.ECPublicKey

interface KeyAttestationProvider {
    fun issue(
        holderId: String,
        issuerId: String?,
        metadata: ResolvedIssuerMetadata,
        configuration: CredentialConfigurationDescriptor,
        proofPublicKey: ECPublicKey,
        proofKeyId: String,
    ): KeyAttestation
}
