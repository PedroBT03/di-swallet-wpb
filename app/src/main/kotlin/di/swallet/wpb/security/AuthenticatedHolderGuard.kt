package di.swallet.wpb.security

import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletUnitRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class AuthenticatedHolderGuard(
    private val holderContext: AuthenticatedHolderContext,
    private val credentialRepository: WalletCredentialRepository,
    private val walletUnitRepository: WalletUnitRepository,
) {
    fun requireSelf(requestedHolderId: String?) {
        val authenticated = holderContext.requireCurrentHolderId()
        if (requestedHolderId.isNullOrBlank() || requestedHolderId != authenticated) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "holderId does not match authenticated user",
            )
        }
    }

    fun requireCredentialOwned(credentialId: Long) {
        val authenticated = holderContext.requireCurrentHolderId()
        val credential = credentialRepository.findById(credentialId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Credential $credentialId not found") }
        if (credential.userId != authenticated) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Credential $credentialId does not belong to authenticated user",
            )
        }
    }

    fun requireWalletUnitOwned(walletId: String) {
        val authenticated = holderContext.requireCurrentHolderId()
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet unit $walletId not found") }
        if (walletUnit.holderId != authenticated) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Wallet unit $walletId does not belong to authenticated user",
            )
        }
    }
}
