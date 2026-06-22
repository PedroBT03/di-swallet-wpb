/**
 * Revokes prior active credentials when a new document of the same family is issued.
 */

package di.swallet.wpb.issuance.storage

import di.swallet.wpb.domain.CredentialRevocationState
import di.swallet.wpb.domain.CredentialTypeLabels
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.revocation.CredentialRevocationGuard
import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Ensures at most one active PID and one active mDL per holder by revoking predecessors on re-issue.
 */
@Service
class IssuedCredentialSupersessionService(
    private val repository: WalletCredentialRepository,
    private val credentialRevocationGuard: CredentialRevocationGuard,
    private val statusListService: StatusListService,
) {
    /** Revokes active wallet credentials of the same document family before storing a replacement. */
    @Transactional
    fun supersedeActiveOfSameFamily(holderId: String, credentialType: String) {
        repository.findByUserId(holderId)
            .asSequence()
            .filter { !credentialRevocationGuard.isRevoked(it) }
            .filter { CredentialTypeLabels.sameDocumentFamily(it.credentialType, credentialType) }
            .forEach { supersede(it) }
    }

    private fun supersede(credential: WalletCredential) {
        if (credentialRevocationGuard.isWpManaged(credential)) {
            credential.statusListIndex?.let { statusListService.revoke(it) }
        }
        credential.revocationState = CredentialRevocationState.REVOKED
        repository.save(credential)
    }
}
