package di.swallet.wpb.ka.status

import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * status index is allocated per attestation fingerprint.
 */
@Service
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
