/**
 * Validates that credentials are presentable and bound to a consumed, non-synthetic key attestation.
 */

package di.swallet.wpb.service

import di.swallet.wpb.domain.AttestedKeyState
import di.swallet.wpb.domain.CredentialBindingFormat
import di.swallet.wpb.domain.CredentialKeyBindingRepository
import di.swallet.wpb.domain.KeyAttestationState
import di.swallet.wpb.revocation.CredentialRevocationGuard
import org.springframework.stereotype.Service

/**
 * Enforces holder-key binding, attestation consumption, and format match before credential use.
 */
@Service
class CredentialBindingValidationService(
    private val credentialKeyBindingRepository: CredentialKeyBindingRepository,
    private val credentialRevocationGuard: CredentialRevocationGuard,
) {
    /**
     * Rejects presentation when the credential is revoked, unbound, or bound for a different format.
     */
    fun requireBinding(credentialId: Long, format: CredentialBindingFormat) {
        credentialRevocationGuard.requirePresentable(credentialId)
        val binding = credentialKeyBindingRepository.findByCredentialId(credentialId)
            .orElseThrow { IllegalStateException("Credential $credentialId has no key binding") }
        require(binding.bindingFormat == format) {
            "Credential $credentialId bound for ${binding.bindingFormat} but requested for $format"
        }
        require(binding.attestedKey.state in setOf(AttestedKeyState.BOUND)) {
            "Credential $credentialId key is not bound"
        }
        val ka = binding.attestedKey.keyAttestation
        require(ka.state == KeyAttestationState.CONSUMED) {
            "Credential $credentialId key attestation is not consumed"
        }
        require(!ka.attestationId.startsWith("synthetic-") && ka.jwt != "synthetic") {
            "Credential $credentialId uses a synthetic key attestation"
        }
    }
}
