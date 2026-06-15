/**
 * Blocks presentation and signing for revoked credentials and invalid wallet keys.
 */

package di.swallet.wpb.revocation

import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.domain.CredentialRevocationState
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.StatusListService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Checks credential and key revocation state before presentation and identifies WP-managed credentials.
 */
@Service
class CredentialRevocationGuard(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val statusListService: StatusListService,
    private val hsmService: HsmService,
    private val properties: StatusListProperties,
) {
    /**
     * Loads a credential by ID and rejects the request if it is revoked or its key is invalid.
     */
    fun requirePresentable(credentialId: Long) {
        val credential = walletCredentialRepository.findById(credentialId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Credential $credentialId not found")
            }
        requirePresentable(credential)
    }

    /**
     * Rejects the request when the credential or its bound wallet key is revoked.
     */
    fun requirePresentable(credential: WalletCredential) {
        if (isRevoked(credential)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Credential ${credential.id} is revoked",
            )
        }
        credential.walletKey?.let { hsmService.validateKeyStatus(it) }
    }

    /**
     * Returns true when the credential is marked revoked locally or in the status list bitstring.
     */
    fun isRevoked(credential: WalletCredential): Boolean {
        if (credential.revocationState == CredentialRevocationState.REVOKED) {
            return true
        }
        credential.statusListIndex?.let { idx ->
            if (statusListService.isRevoked(idx)) {
                return true
            }
        }
        return false
    }

    /**
     * Returns true when the wallet provider owns both the status list ID and index for the credential.
     */
    fun isWpManaged(credential: WalletCredential): Boolean =
        credential.statusListId != null && credential.statusListIndex != null

    /**
     * Builds the public URI clients use to fetch the WP-managed status list credential.
     */
    fun statusListUri(): String =
        "${properties.publicBaseUrl.trimEnd('/')}/${statusListService.getListId()}"
}
