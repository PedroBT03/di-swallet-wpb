package di.swallet.wpb.ka.status

import di.swallet.wpb.domain.KaStatusIndex
import di.swallet.wpb.domain.KaStatusIndexRepository
import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.service.StatusListService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Primary
class JpaKaStatusManagementService(
    private val repository: KaStatusIndexRepository,
    private val statusListService: StatusListService,
) : KaStatusManagementService {

    @Transactional
    override fun getOrAllocateStatus(
        holderId: String,
        issuerId: String?,
        attestationFingerprint: String,
    ): KaStatusReference {
        val scope = issuerId ?: "*"
        val existing = repository.findByHolderIdAndIssuerScopeAndAttestationFingerprint(
            holderId,
            scope,
            attestationFingerprint,
        )
        if (existing.isPresent) {
            return toReference(existing.get())
        }
        val allocated = statusListService.getNextRevocationIndex()
        val saved = repository.save(
            KaStatusIndex(
                holderId = holderId,
                issuerScope = scope,
                attestationFingerprint = attestationFingerprint,
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

    private fun toReference(entry: KaStatusIndex): KaStatusReference =
        KaStatusReference(
            listId = entry.listId,
            index = entry.statusIndex,
            uri = "/api/v1/wallet/status-lists/${entry.listId}",
        )
}
