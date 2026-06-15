package di.swallet.wpb.service

import di.swallet.wpb.domain.AttestedKeyRecordRepository
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.ka.attestation.KeyAttestationProvider
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Demo-only helper for the legacy `/credentials/issue-sd` path.
 * Registers a real KA (not synthetic) before credential-to-key binding.
 */
@Service
@Profile("dev")
class LegacySdJwtIssuanceSupport(
    private val keyAttestationProvider: KeyAttestationProvider,
    private val keyBindingRuntimeService: KeyBindingRuntimeService,
    private val attestedKeyRepository: AttestedKeyRecordRepository,
    private val walletUnitLifecycleService: WalletUnitLifecycleService,
) {
    fun ensureKaForHolderKey(holderId: String, keyAlias: String, publicKeyBase64: String) {
        if (attestedKeyRepository.findByKeyAlias(keyAlias).isPresent) return
        walletUnitLifecycleService.requireIssuanceEligible(holderId)
        val publicKey = decodeEcPublicKey(publicKeyBase64)
        val metadata = ResolvedIssuerMetadata(credentialIssuerId = "https://legacy.wpb.local")
        val configuration = CredentialConfigurationDescriptor(
            id = "legacy_pid",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            keyAttestationRequired = true,
            proofTypesSupported = listOf("jwt", "attestation"),
        )
        val attestation = keyAttestationProvider.issue(
            holderId = holderId,
            issuerId = null,
            metadata = metadata,
            configuration = configuration,
            proofPublicKey = publicKey,
            proofKeyId = keyAlias,
        )
        keyBindingRuntimeService.registerKeyAttestation(holderId, attestation)
    }

    private fun decodeEcPublicKey(publicKeyBase64: String): ECPublicKey {
        val bytes = Base64.getUrlDecoder().decode(publicKeyBase64)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        return key as ECPublicKey
    }
}
