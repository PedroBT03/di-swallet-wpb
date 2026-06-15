/**
 * Spring Data repository for FIDO2 user device credentials.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Loads registered FIDO2 devices by holder user ID or WebAuthn credential ID.
 */
@Repository
interface UserDeviceRepository : JpaRepository<UserDevice, Long> {
    /** Returns all devices registered for a wallet holder. */
    fun findByUserId(userId: String): List<UserDevice>

    /** Finds a device by its WebAuthn credential identifier. */
    fun findByCredentialId(credentialId: String): Optional<UserDevice>
}
