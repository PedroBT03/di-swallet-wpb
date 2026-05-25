package di.swallet.wpb.wia.status

import di.swallet.wpb.issuance.domain.WiaStatusReference
import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 MVP status management for WIA.
 *
 * Uses the existing global bitstring status list infrastructure and keeps an
 * in-memory mapping from holder(+issuer when reuse is enabled) to list index.
 */
@Service
class InMemoryWiaStatusManagementService(
    private val statusListService: StatusListService,
) : WiaStatusManagementService {

    private val byHolderIssuer = ConcurrentHashMap<String, Int>()
    private val holderEntries = ConcurrentHashMap<String, MutableSet<Int>>()

    override fun getOrAllocateStatus(holderId: String, issuerId: String?): WiaStatusReference {
        val key = buildKey(holderId, issuerId)
        val idx = byHolderIssuer.computeIfAbsent(key) {
            val allocated = statusListService.getNextRevocationIndex()
            holderEntries.computeIfAbsent(holderId) { mutableSetOf() }.add(allocated)
            allocated
        }
        return WiaStatusReference(
            listId = statusListService.getListId(),
            index = idx,
            uri = "/api/v1/wallet/status-lists/${statusListService.getListId()}",
        )
    }

    override fun revokeHolder(holderId: String) {
        holderEntries[holderId]?.forEach { idx ->
            statusListService.revoke(idx)
        }
    }

    private fun buildKey(holderId: String, issuerId: String?): String =
        "$holderId::${issuerId ?: "*"}"
}

