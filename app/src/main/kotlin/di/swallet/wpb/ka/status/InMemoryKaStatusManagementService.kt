package di.swallet.wpb.ka.status

import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.service.StatusListService
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory KA status mapping for isolated unit tests.
 *
 * Production wiring uses [JpaKaStatusManagementService].
 */
class InMemoryKaStatusManagementService(
    private val statusListService: StatusListService,
) : KaStatusManagementService {
    private val byHolderIssuerAttestation = ConcurrentHashMap<String, Int>()
    private val holderEntries = ConcurrentHashMap<String, MutableSet<Int>>()

    override fun getOrAllocateStatus(
        holderId: String,
        issuerId: String?,
        attestationFingerprint: String,
    ): KaStatusReference {
        val key = "$holderId::${issuerId ?: "*"}::$attestationFingerprint"
        val idx = byHolderIssuerAttestation.computeIfAbsent(key) {
            val allocated = statusListService.getNextRevocationIndex()
            holderEntries.computeIfAbsent(holderId) { mutableSetOf() }.add(allocated)
            allocated
        }
        return KaStatusReference(
            listId = statusListService.getListId(),
            index = idx,
            uri = "/api/v1/wallet/status-lists/${statusListService.getListId()}",
        )
    }

    override fun revokeHolder(holderId: String) {
        holderEntries[holderId]?.forEach { statusListService.revoke(it) }
    }
}
