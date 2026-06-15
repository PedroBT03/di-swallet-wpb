/**
 * Spring Data repository for WIA status index mappings.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Loads WIA status-list index rows by holder and issuer scope.
 */
interface WiaStatusIndexRepository : JpaRepository<WiaStatusIndex, Long> {
    /** Finds the status index for a holder within a specific issuer scope. */
    fun findByHolderIdAndIssuerScope(holderId: String, issuerScope: String): Optional<WiaStatusIndex>

    /** Returns all WIA status index rows for a holder across issuer scopes. */
    fun findAllByHolderId(holderId: String): List<WiaStatusIndex>
}
