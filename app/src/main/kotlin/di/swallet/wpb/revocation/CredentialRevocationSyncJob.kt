package di.swallet.wpb.revocation

import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.domain.CredentialRevocationState
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.service.StatusListService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Background sync of denormalized revocation state (VCR_19).
 * Presentation gates always consult the bitstring in real time.
 */
@Component
class CredentialRevocationSyncJob(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val statusListService: StatusListService,
    private val properties: StatusListProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${wpb.status-list.sync-cron:0 0 * * * *}")
    @Transactional
    fun syncRevocationState() {
        var updated = 0
        walletCredentialRepository.findAll().forEach { credential ->
            val index = credential.statusListIndex ?: return@forEach
            val bitRevoked = statusListService.isRevoked(index)
            if (bitRevoked && credential.revocationState != CredentialRevocationState.REVOKED) {
                credential.revocationState = CredentialRevocationState.REVOKED
                walletCredentialRepository.save(credential)
                updated++
            }
        }
        if (updated > 0) {
            logger.info("CredentialRevocationSyncJob: synced {} credentials to REVOKED", updated)
        }
    }
}
