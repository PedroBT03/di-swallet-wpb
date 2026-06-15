/**
 * Spring Data repository for pseudonym credential persistence and holder-scoped queries.
 */

package di.swallet.wpb.pseudonym

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Loads and counts pseudonym credentials by holder and relying party.
 */
interface PseudonymCredentialRepository : JpaRepository<PseudonymCredential, UUID> {
    /** Returns all pseudonyms registered for the given wallet holder. */
    fun findByHolderId(holderId: String): List<PseudonymCredential>

    /** Returns pseudonyms for a holder scoped to a specific relying party ID. */
    fun findByHolderIdAndRpId(holderId: String, rpId: String): List<PseudonymCredential>

    /** Counts how many pseudonyms a holder already has for one relying party. */
    fun countByHolderIdAndRpId(holderId: String, rpId: String): Long
}
