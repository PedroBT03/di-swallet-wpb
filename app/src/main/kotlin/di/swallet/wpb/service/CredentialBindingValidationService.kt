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
