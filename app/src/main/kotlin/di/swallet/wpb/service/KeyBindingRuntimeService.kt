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
import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.issuance.domain.KeyAttestation
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class KeyBindingRuntimeService(
    private val walletUnitRepository: WalletUnitRepository,
    private val keyAttestationRepository: KeyAttestationRecordRepository,
    private val attestedKeyRepository: AttestedKeyRecordRepository,
    private val credentialRepository: WalletCredentialRepository,
    private val credentialKeyBindingRepository: CredentialKeyBindingRepository,
) {
    fun registerKeyAttestation(holderId: String, ka: KeyAttestation): KeyAttestationRecord {
        val walletUnit = ensureWalletUnit(holderId)
        val existing = keyAttestationRepository.findByAttestationId(attestationId(ka)).orElse(null)
        if (existing != null) return existing

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
            ?: createSyntheticAttestedKey(credential.userId, keyAlias)

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
    }

    private fun createSyntheticAttestedKey(holderId: String, keyAlias: String): AttestedKeyRecord {
        val walletUnit = ensureWalletUnit(holderId)
        val syntheticKa = keyAttestationRepository.save(
            KeyAttestationRecord(
                walletUnit = walletUnit,
                attestationId = "synthetic-$keyAlias",
                jwt = "synthetic",
                keyStorage = "unknown",
                statusListUri = "n/a",
                statusListIndex = -1,
                technicalExpiresAt = Instant.now().plusSeconds(365 * 24 * 60 * 60L),
                statusMaintenanceExpiresAt = Instant.now().plusSeconds(365 * 24 * 60 * 60L),
                issuedAt = Instant.now(),
                state = KeyAttestationState.CONSUMED,
                consumedAt = Instant.now(),
            ),
        )
        return attestedKeyRepository.save(
            AttestedKeyRecord(
                keyAttestation = syntheticKa,
                keyAlias = keyAlias,
                keyThumbprint = "synthetic-$keyAlias",
                batchPosition = 0,
                state = AttestedKeyState.BOUND,
            ),
        )
    }

    private fun ensureWalletUnit(holderId: String): WalletUnit {
        return walletUnitRepository.findFirstByHolderId(holderId).orElseGet {
            walletUnitRepository.save(
                WalletUnit(
                    holderId = holderId,
                    state = di.swallet.wpb.domain.WalletUnitState.OPERATIONAL,
                    walletId = UUID.randomUUID().toString(),
                ),
            )
        }
    }

    private fun attestationId(ka: KeyAttestation): String =
        "${ka.keyId}:${ka.issuedAt.epochSecond}:${ka.attestedJkt}"
}
