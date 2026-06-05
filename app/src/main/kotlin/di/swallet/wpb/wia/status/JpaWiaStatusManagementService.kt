package di.swallet.wpb.wia.status

import di.swallet.wpb.domain.WiaStatusIndex
import di.swallet.wpb.domain.WiaStatusIndexRepository
import di.swallet.wpb.issuance.domain.WiaStatusReference
import di.swallet.wpb.service.StatusListService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Primary
class JpaWiaStatusManagementService(
    private val repository: WiaStatusIndexRepository,
    private val statusListService: StatusListService,
) : WiaStatusManagementService {

    @Transactional
    override fun getOrAllocateStatus(holderId: String, issuerId: String?): WiaStatusReference {
        val scope = issuerId ?: "*"
        val existing = repository.findByHolderIdAndIssuerScope(holderId, scope)
        if (existing.isPresent) {
            return toReference(existing.get())
        }
        val allocated = statusListService.getNextRevocationIndex()
        val saved = repository.save(
            WiaStatusIndex(
                holderId = holderId,
                issuerScope = scope,
                listId = statusListService.getListId(),
                statusIndex = allocated,
            ),
        )
        return toReference(saved)
    }

    @Transactional
    override fun revokeHolder(holderId: String) {
        repository.findAllByHolderId(holderId).forEach { entry ->
            statusListService.revoke(entry.statusIndex)
        }
    }

    private fun toReference(entry: WiaStatusIndex): WiaStatusReference =
        WiaStatusReference(
            listId = entry.listId,
            index = entry.statusIndex,
            uri = "/api/v1/wallet/status-lists/${entry.listId}",
        )
}
