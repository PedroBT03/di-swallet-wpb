/**
 * JPA-backed key attestation status list index allocation and revocation.
 */

package di.swallet.wpb.ka.status

import di.swallet.wpb.domain.KaStatusIndex
import di.swallet.wpb.domain.KaStatusIndexRepository
import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.service.StatusListService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Production KA status management backed by persistent holder-to-index mappings. */
@Service
@Primary
class JpaKaStatusManagementService(
    private val repository: KaStatusIndexRepository,
    private val statusListService: StatusListService,
) : KaStatusManagementService {

    @Transactional
    /** Reuses a stored index or allocates the next slot for the attestation fingerprint. */
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
    /** Revokes every status index recorded for the holder across attestation fingerprints. */
    override fun revokeHolder(holderId: String) {
        repository.findAllByHolderId(holderId).forEach { entry ->
            statusListService.revoke(entry.statusIndex)
        }
    }

    /** Maps a persisted index row to the key attestation status reference DTO. */
    private fun toReference(entry: KaStatusIndex): KaStatusReference =
        KaStatusReference(
            listId = entry.listId,
            index = entry.statusIndex,
            uri = "/api/v1/wallet/status-lists/${entry.listId}",
        )
}
