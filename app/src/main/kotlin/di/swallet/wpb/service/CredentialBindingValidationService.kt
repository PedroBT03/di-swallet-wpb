package di.swallet.wpb.service

import di.swallet.wpb.domain.AttestedKeyState
import di.swallet.wpb.domain.CredentialBindingFormat
import di.swallet.wpb.domain.CredentialKeyBindingRepository
import di.swallet.wpb.domain.KeyAttestationState
import org.springframework.stereotype.Service

@Service
class CredentialBindingValidationService(
    private val credentialKeyBindingRepository: CredentialKeyBindingRepository,
) {
    fun requireBinding(credentialId: Long, format: CredentialBindingFormat) {
        val binding = credentialKeyBindingRepository.findByCredentialId(credentialId)
            .orElseThrow { IllegalStateException("Credential $credentialId has no key binding") }
        require(binding.bindingFormat == format) {
            "Credential $credentialId bound for ${binding.bindingFormat} but requested for $format"
        }
        require(binding.attestedKey.state in setOf(AttestedKeyState.ATTESTED, AttestedKeyState.BOUND)) {
            "Credential $credentialId key is not active"
        }
        require(binding.attestedKey.keyAttestation.state in setOf(KeyAttestationState.AVAILABLE, KeyAttestationState.CONSUMED)) {
            "Credential $credentialId key attestation is not valid"
        }
    }
}
