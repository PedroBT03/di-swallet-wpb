package di.swallet.wpb.revocation

import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Component

@Component
class WpCredentialStatusAllocator(
    private val statusListService: StatusListService,
    private val credentialRevocationGuard: CredentialRevocationGuard,
) {
    data class Allocation(
        val listId: String,
        val index: Int,
        val listUri: String,
    )

    fun allocate(): Allocation {
        val listId = statusListService.getListId()
        val index = statusListService.allocateRandomIndex()
        return Allocation(listId, index, credentialRevocationGuard.statusListUri())
    }

    fun buildCredentialStatusClaim(allocation: Allocation): Map<String, Any> =
        mapOf(
            "id" to "${allocation.listUri}#${allocation.index}",
            "type" to "BitstringStatusListEntry",
            "statusPurpose" to "revocation",
            "statusListIndex" to allocation.index,
            "statusListCredential" to allocation.listUri,
        )
}
