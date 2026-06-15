/**
 * Spring Data repository for wallet credential persistence and holder-scoped queries.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Loads stored credentials by database ID or wallet holder user ID.
 */
@Repository
interface WalletCredentialRepository : JpaRepository<WalletCredential, Long> {
    /** Returns all credentials issued to the given holder. */
    fun findByUserId(userId: String): List<WalletCredential>
}
