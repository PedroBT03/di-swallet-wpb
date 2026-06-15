/**
 * Secure Cryptographic Interface guard between wallet logic and HSM operations.
 */

package di.swallet.wpb.security

import di.swallet.wpb.config.WscaSciProperties
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.pseudonym.PseudonymCredentialRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Secure Cryptographic Interface (SCI) guard between Wallet Instance logic and [HsmService].
 */
@Component
class WscaAccessGuard(
    private val properties: WscaSciProperties,
    private val authenticatedHolderContext: AuthenticatedHolderContext,
    private val sciGrantService: WscaSciGrantService,
    private val walletKeyRepository: WalletKeyRepository,
    private val pseudonymCredentialRepository: PseudonymCredentialRepository,
) {
    /**
     * Requires SCI authorization before HSM use on behalf of the given holder.
     */
    fun requireSciForHolder(holderId: String) {
        if (!properties.enforceSciBoundary || holderId.isBlank()) return
        if (WscaSciBootstrap.isActive()) return
        val requestHolder = authenticatedHolderContext.currentHolderId()
        if (requestHolder == holderId) return
        if (sciGrantService.isGranted(holderId)) return
        throw ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "WSCA SCI: HSM operation not authorized for holder $holderId",
        )
    }

    /**
     * Resolves the holder for a wallet key alias and applies SCI checks.
     */
    fun requireSciForWalletKeyAlias(keyAlias: String) {
        val walletKey = walletKeyRepository.findByKeyAlias(keyAlias).orElse(null)
        if (walletKey != null) {
            requireSciForHolder(walletKey.userId)
            return
        }
        requireSciForDedicatedAlias(keyAlias)
    }

    /**
     * Applies SCI checks for dedicated pseudonym HSM aliases.
     */
    fun requireSciForDedicatedAlias(alias: String) {
        if (!properties.enforceSciBoundary) return
        if (WscaSciBootstrap.isActive()) return
        val prefix = "pseudonym-"
        if (!alias.startsWith(prefix)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "WSCA SCI: unknown dedicated HSM alias")
        }
        val credentialId = runCatching { UUID.fromString(alias.removePrefix(prefix)) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "WSCA SCI: invalid pseudonym alias")
        val credential = pseudonymCredentialRepository.findById(credentialId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "WSCA SCI: pseudonym credential not found")
        requireSciForHolder(credential.holderId)
    }

    /**
     * Requires any authorized holder context before a generic HSM operation proceeds.
     */
    fun requireSciAuthorization() {
        if (!properties.enforceSciBoundary) return
        if (WscaSciBootstrap.isActive()) return
        val requestHolder = authenticatedHolderContext.currentHolderId()
        if (!requestHolder.isNullOrBlank()) return
        throw ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "WSCA SCI: no authorized holder context for HSM operation",
        )
    }
}
