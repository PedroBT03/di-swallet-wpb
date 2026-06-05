package di.swallet.wpb.service

import di.swallet.wpb.domain.AttestedKeyRecord
import di.swallet.wpb.domain.AttestedKeyRecordRepository
import di.swallet.wpb.domain.AttestedKeyState
import di.swallet.wpb.domain.CredentialBindingFormat
import di.swallet.wpb.domain.CredentialKeyBinding
import di.swallet.wpb.domain.CredentialKeyBindingRepository
import di.swallet.wpb.domain.KeyAttestationRecord
import di.swallet.wpb.domain.KeyAttestationRecordRepository
import di.swallet.wpb.domain.KeyAttestationState
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.issuance.domain.KeyAttestation
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Service
class KeyBindingRuntimeService(
    private val walletUnitRepository: WalletUnitRepository,
    private val walletUnitLifecycleService: WalletUnitLifecycleService,
    private val keyAttestationRepository: KeyAttestationRecordRepository,
    private val attestedKeyRepository: AttestedKeyRecordRepository,
    private val credentialRepository: WalletCredentialRepository,
    private val credentialKeyBindingRepository: CredentialKeyBindingRepository,
    private val walletKeyRepository: WalletKeyRepository,
) {
    fun registerKeyAttestation(holderId: String, ka: KeyAttestation): KeyAttestationRecord {
        val walletUnit = walletUnitLifecycleService.requireIssuanceEligible(holderId)
        val existing = keyAttestationRepository.findByAttestationId(attestationId(ka)).orElse(null)
        if (existing != null) return existing

        val walletKey = walletKeyRepository.findByKeyAlias(ka.keyId).orElse(null)
        val kaRecord = keyAttestationRepository.save(
            KeyAttestationRecord(
                walletUnit = walletUnit,
                attestationId = attestationId(ka),
                jwt = ka.jwt,
                keyStorage = ka.keyStorage,
                statusListUri = ka.status.uri,
                statusListIndex = ka.status.index,
                technicalExpiresAt = ka.tokenExpiresAt,
                statusMaintenanceExpiresAt = ka.statusExpiresAt,
                issuedAt = ka.issuedAt,
                state = KeyAttestationState.AVAILABLE,
            ),
        )
        attestedKeyRepository.save(
            AttestedKeyRecord(
                keyAttestation = kaRecord,
                keyAlias = ka.keyId,
                keyThumbprint = ka.attestedJkt,
                batchPosition = 0,
                state = AttestedKeyState.ATTESTED,
                walletKey = walletKey,
            ),
        )
        return kaRecord
    }

    fun bindCredentialToKey(
        credentialId: Long,
        keyAlias: String,
        format: CredentialBindingFormat,
    ) {
        val credential = credentialRepository.findById(credentialId)
            .orElseThrow { IllegalArgumentException("credential '$credentialId' not found") }
        if (credentialKeyBindingRepository.findByCredentialId(credentialId).isPresent) return

        val attestedKey = attestedKeyRepository.findByKeyAlias(keyAlias).orElse(null)
            ?: throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "No attested key for alias '$keyAlias'; synthetic KA bypass is disabled",
            )

        credentialKeyBindingRepository.save(
            CredentialKeyBinding(
                credential = credential,
                attestedKey = attestedKey,
                bindingFormat = format,
                boundAt = Instant.now(),
            ),
        )
        if (attestedKey.state == AttestedKeyState.ATTESTED) {
            attestedKeyRepository.save(
                AttestedKeyRecord(
                    id = attestedKey.id,
                    keyAttestation = attestedKey.keyAttestation,
                    keyId = attestedKey.keyId,
                    keyAlias = attestedKey.keyAlias,
                    keyThumbprint = attestedKey.keyThumbprint,
                    batchPosition = attestedKey.batchPosition,
                    createdAt = attestedKey.createdAt,
                    state = AttestedKeyState.BOUND,
                    walletKey = attestedKey.walletKey,
                ),
            )
        }
        val ka = attestedKey.keyAttestation
        if (ka.state == KeyAttestationState.AVAILABLE) {
            keyAttestationRepository.save(
                KeyAttestationRecord(
                    id = ka.id,
                    walletUnit = ka.walletUnit,
                    attestationId = ka.attestationId,
                    jwt = ka.jwt,
                    keyStorage = ka.keyStorage,
                    statusListUri = ka.statusListUri,
                    statusListIndex = ka.statusListIndex,
                    technicalExpiresAt = ka.technicalExpiresAt,
                    statusMaintenanceExpiresAt = ka.statusMaintenanceExpiresAt,
                    issuedAt = ka.issuedAt,
                    consumedAt = Instant.now(),
                    state = KeyAttestationState.CONSUMED,
                ),
            )
        }

        credential.userId.let { holderId ->
            walletUnitRepository.findFirstByHolderId(holderId).ifPresent { walletUnit ->
                if (walletUnit.state == di.swallet.wpb.domain.WalletUnitState.OPERATIONAL) {
                    walletUnitLifecycleService.markValid(walletUnit)
                }
            }
        }
    }

    private fun attestationId(ka: KeyAttestation): String =
        "${ka.keyId}:${ka.issuedAt.epochSecond}:${ka.attestedJkt}"
}
