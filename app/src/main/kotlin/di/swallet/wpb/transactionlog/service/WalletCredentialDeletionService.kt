package di.swallet.wpb.transactionlog.service

import di.swallet.wpb.domain.WalletCredentialRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class WalletCredentialDeletionService(
    private val credentialRepository: WalletCredentialRepository,
    private val transactionLogger: TransactionLogger,
) {
    @Transactional
    fun deleteCredential(credentialId: Long) {
        val credential = credentialRepository.findById(credentialId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Credential $credentialId not found") }

        transactionLogger.logCredentialDeletion(credential)
        credentialRepository.delete(credential)
    }
}
