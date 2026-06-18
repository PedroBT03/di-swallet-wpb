/**
 * Authorization checks that bind wallet resources to the FIDO2-authenticated holder.
 */

package di.swallet.wpb.security

import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletUnitRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Verifies that requested holder, credential, and wallet unit ids belong to the authenticated user.
 */
@Component
class AuthenticatedHolderGuard(
    private val holderContext: AuthenticatedHolderContext,
    private val credentialRepository: WalletCredentialRepository,
    private val walletUnitRepository: WalletUnitRepository,
) {
    /**
     * Rejects the call when the requested holder id is not the authenticated holder.
     */
    fun requireSelf(requestedHolderId: String?, request: HttpServletRequest? = null) {
        val authenticated = holderContext.requireCurrentHolderId(request)
        if (requestedHolderId.isNullOrBlank() || requestedHolderId != authenticated) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "holderId does not match authenticated user",
            )
        }
    }

    /**
     * Rejects the call when the credential does not belong to the authenticated holder.
     */
    fun requireCredentialOwned(credentialId: Long, request: HttpServletRequest? = null) {
        val authenticated = holderContext.requireCurrentHolderId(request)
        val credential = credentialRepository.findById(credentialId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Credential $credentialId not found") }
        if (credential.userId != authenticated) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Credential $credentialId does not belong to authenticated user",
            )
        }
    }

    /**
     * Rejects the call when the wallet unit does not belong to the authenticated holder.
     */
    fun requireWalletUnitOwned(walletId: String, request: HttpServletRequest? = null) {
        val authenticated = holderContext.requireCurrentHolderId(request)
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
