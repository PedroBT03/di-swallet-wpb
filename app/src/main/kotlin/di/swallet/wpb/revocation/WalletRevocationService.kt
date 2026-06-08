package di.swallet.wpb.revocation

import di.swallet.wpb.domain.CredentialRevocationState
import di.swallet.wpb.domain.KeyAttestationRecord
import di.swallet.wpb.domain.KeyAttestationRecordRepository
import di.swallet.wpb.domain.KeyAttestationState
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.ka.status.KaStatusManagementService
import di.swallet.wpb.service.StatusListService
import di.swallet.wpb.service.WalletUnitLifecycleService
import di.swallet.wpb.wia.status.WiaStatusManagementService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class WalletRevocationService(
    private val walletUnitRepository: WalletUnitRepository,
    private val walletUnitLifecycleService: WalletUnitLifecycleService,
    private val wiaStatusManagementService: WiaStatusManagementService,
    private val kaStatusManagementService: KaStatusManagementService,
    private val keyAttestationRepository: KeyAttestationRecordRepository,
    private val walletKeyRepository: WalletKeyRepository,
    private val walletCredentialRepository: WalletCredentialRepository,
    private val statusListService: StatusListService,
    private val credentialRevocationGuard: CredentialRevocationGuard,
) {
    @Transactional
    fun revokeCredential(credentialId: Long) {
        val credential = walletCredentialRepository.findById(credentialId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Credential $credentialId not found")
            }
        if (!credentialRevocationGuard.isWpManaged(credential)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Credential $credentialId is issuer-managed; revocation must be performed by the issuer",
            )
        }
        val index = credential.statusListIndex
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Credential has no WP status index")
        statusListService.revoke(index)
        credential.revocationState = CredentialRevocationState.REVOKED
        walletCredentialRepository.save(credential)
    }

    @Transactional
    fun revokeWalletUnit(walletId: String) {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet unit $walletId not found")
            }
        val holderId = walletUnit.holderId
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Wallet unit has no holder")

        walletUnitLifecycleService.revoke(walletUnit)

        wiaStatusManagementService.revokeHolder(holderId)
        kaStatusManagementService.revokeHolder(holderId)
        markKeyAttestationsRevoked(walletUnit)

        walletKeyRepository.findByUserId(holderId).ifPresent { key ->
            statusListService.revoke(key.revocationIndex)
        }

        walletCredentialRepository.findByUserId(holderId)
            .filter { credentialRevocationGuard.isWpManaged(it) }
            .forEach { credential ->
                credential.statusListIndex?.let { statusListService.revoke(it) }
                credential.revocationState = CredentialRevocationState.REVOKED
                walletCredentialRepository.save(credential)
            }
    }

    private fun markKeyAttestationsRevoked(walletUnit: WalletUnit) {
        val unitId = walletUnit.id ?: return
        keyAttestationRepository.findByWalletUnitId(unitId).forEach { record ->
            if (record.state != KeyAttestationState.REVOKED) {
                keyAttestationRepository.save(
                    KeyAttestationRecord(
                        id = record.id,
                        walletUnit = record.walletUnit,
                        attestationId = record.attestationId,
                        jwt = record.jwt,
                        keyStorage = record.keyStorage,
                        statusListUri = record.statusListUri,
                        statusListIndex = record.statusListIndex,
                        technicalExpiresAt = record.technicalExpiresAt,
                        statusMaintenanceExpiresAt = record.statusMaintenanceExpiresAt,
                        issuedAt = record.issuedAt,
                        consumedAt = record.consumedAt,
                        state = KeyAttestationState.REVOKED,
                    ),
                )
            }
        }
    }
}
