/**
 * Allocates WP-managed status list indices and builds credentialStatus claims for issuance.
 */

package di.swallet.wpb.revocation

import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Component

/**
 * Reserves status list slots and formats BitstringStatusListEntry claims for newly issued credentials.
 */
@Component
class WpCredentialStatusAllocator(
    private val statusListService: StatusListService,
    private val credentialRevocationGuard: CredentialRevocationGuard,
) {
    /** Status list ID, bit index, and public list URI assigned to one credential. */
    data class Allocation(
        val listId: String,
        val index: Int,
        val listUri: String,
    )

    /**
     * Allocates a random free index in the primary status list and returns its public reference.
     */
    fun allocate(): Allocation {
        val listId = statusListService.getListId()
        val index = statusListService.allocateRandomIndex()
        return Allocation(listId, index, credentialRevocationGuard.statusListUri())
    }

    /**
     * Builds the `credentialStatus` claim map for a WP-managed BitstringStatusListEntry.
     */
    fun buildCredentialStatusClaim(allocation: Allocation): Map<String, Any> =
        mapOf(
            "id" to "${allocation.listUri}#${allocation.index}",
            "type" to "BitstringStatusListEntry",
            "statusPurpose" to "revocation",
            "statusListIndex" to allocation.index,
            "statusListCredential" to allocation.listUri,
        )
}
