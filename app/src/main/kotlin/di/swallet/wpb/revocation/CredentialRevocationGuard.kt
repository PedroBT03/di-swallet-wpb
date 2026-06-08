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

@Service
class CredentialRevocationGuard(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val statusListService: StatusListService,
    private val hsmService: HsmService,
    private val properties: StatusListProperties,
) {
    fun requirePresentable(credentialId: Long) {
        val credential = walletCredentialRepository.findById(credentialId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Credential $credentialId not found")
            }
        requirePresentable(credential)
    }

    fun requirePresentable(credential: WalletCredential) {
        if (isRevoked(credential)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Credential ${credential.id} is revoked",
            )
        }
        credential.walletKey?.let { hsmService.validateKeyStatus(it) }
    }

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

    fun isWpManaged(credential: WalletCredential): Boolean =
        credential.statusListId != null && credential.statusListIndex != null

    fun statusListUri(): String =
        "${properties.publicBaseUrl.trimEnd('/')}/${statusListService.getListId()}"
}
