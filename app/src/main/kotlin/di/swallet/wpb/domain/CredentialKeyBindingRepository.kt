package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface CredentialKeyBindingRepository : JpaRepository<CredentialKeyBinding, Long> {
    fun findByCredentialId(credentialId: Long): Optional<CredentialKeyBinding>
    fun findByAttestedKeyId(attestedKeyId: Long): List<CredentialKeyBinding>
}
