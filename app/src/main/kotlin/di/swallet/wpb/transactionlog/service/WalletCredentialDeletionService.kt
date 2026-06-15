/**
 * Deletes wallet credentials and logs the deletion in the TS10 transaction log.
 */

package di.swallet.wpb.transactionlog.service

import di.swallet.wpb.domain.WalletCredentialRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/** Removes a credential from storage after recording a CredentialDeletion transaction. */
@Service
class WalletCredentialDeletionService(
    private val credentialRepository: WalletCredentialRepository,
    private val transactionLogger: TransactionLogger,
) {
    /** Logs credential deletion and deletes the credential row. Throws 404 if the credential does not exist. */
    @Transactional
    fun deleteCredential(credentialId: Long) {
        val credential = credentialRepository.findById(credentialId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Credential $credentialId not found") }

        transactionLogger.logCredentialDeletion(credential)
        credentialRepository.delete(credential)
    }
}
