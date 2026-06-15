/**
 * Spring Data repository for credential-to-key binding lookups.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Finds credential key bindings by credential or attested key ID.
 */
interface CredentialKeyBindingRepository : JpaRepository<CredentialKeyBinding, Long> {
    /** Returns the binding for a credential, if one exists. */
    fun findByCredentialId(credentialId: Long): Optional<CredentialKeyBinding>

    /** Returns all bindings that reference the given attested key. */
    fun findByAttestedKeyId(attestedKeyId: Long): List<CredentialKeyBinding>
}
