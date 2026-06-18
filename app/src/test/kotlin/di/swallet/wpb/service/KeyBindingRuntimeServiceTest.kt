/**
 * Tests idempotent key attestation registration for repeated issuance attempts.
 */

package di.swallet.wpb.service

import di.swallet.wpb.domain.AttestedKeyRecord
import di.swallet.wpb.domain.AttestedKeyRecordRepository
import di.swallet.wpb.domain.AttestedKeyState
import di.swallet.wpb.domain.CredentialKeyBindingRepository
import di.swallet.wpb.domain.KeyAttestationRecord
import di.swallet.wpb.domain.KeyAttestationRecordRepository
import di.swallet.wpb.domain.KeyAttestationState
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.issuance.domain.KeyAttestation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.Instant
import java.util.Optional

class KeyBindingRuntimeServiceTest {

    private val walletUnitRepository = mock(WalletUnitRepository::class.java)
    private val walletUnitLifecycleService = mock(WalletUnitLifecycleService::class.java)
    private val keyAttestationRepository = mock(KeyAttestationRecordRepository::class.java)
    private val attestedKeyRepository = mock(AttestedKeyRecordRepository::class.java)
    private val credentialRepository = mock(WalletCredentialRepository::class.java)
    private val credentialKeyBindingRepository = mock(CredentialKeyBindingRepository::class.java)
    private val walletKeyRepository = mock(WalletKeyRepository::class.java)

    private val service = KeyBindingRuntimeService(
        walletUnitRepository,
        walletUnitLifecycleService,
        keyAttestationRepository,
        attestedKeyRepository,
        credentialRepository,
        credentialKeyBindingRepository,
        walletKeyRepository,
    )

    @Test
    fun `registerKeyAttestation updates existing attested key for same alias`() {
        val holderId = "demo-1"
        val walletUnit = WalletUnit(id = 1L, holderId = holderId, walletId = "w-1")
        val keyAlias = "key-demo-1-1781632997085"
        val existingKa = KeyAttestationRecord(
            id = 10L,
            walletUnit = walletUnit,
            attestationId = "old",
            jwt = "old-jwt",
            keyStorage = "iso_18045_high",
            statusListUri = "http://localhost/status",
            statusListIndex = 1,
            technicalExpiresAt = Instant.now().plusSeconds(3600),
            statusMaintenanceExpiresAt = Instant.now().plusSeconds(7200),
            issuedAt = Instant.now().minusSeconds(60),
            state = KeyAttestationState.CONSUMED,
        )
        val existingAttested = AttestedKeyRecord(
            id = 20L,
            keyAttestation = existingKa,
            keyAlias = keyAlias,
            keyThumbprint = "old-jkt",
            state = AttestedKeyState.BOUND,
        )
        val ka = sampleKa(keyAlias, issuedAt = Instant.now())

        `when`(walletUnitLifecycleService.requireIssuanceEligible(holderId)).thenReturn(walletUnit)
        `when`(keyAttestationRepository.findByAttestationId(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty())
        `when`(walletKeyRepository.findByKeyAlias(keyAlias)).thenReturn(Optional.empty())
        `when`(keyAttestationRepository.save(any(KeyAttestationRecord::class.java))).thenAnswer { invocation ->
            val record = invocation.getArgument<KeyAttestationRecord>(0)
            KeyAttestationRecord(
                id = 11L,
                walletUnit = record.walletUnit,
                attestationId = record.attestationId,
                jwt = record.jwt,
                keyStorage = record.keyStorage,
                statusListUri = record.statusListUri,
                statusListIndex = record.statusListIndex,
                technicalExpiresAt = record.technicalExpiresAt,
                statusMaintenanceExpiresAt = record.statusMaintenanceExpiresAt,
                issuedAt = record.issuedAt,
                state = record.state,
            )
        }
        `when`(attestedKeyRepository.findByKeyAlias(keyAlias)).thenReturn(Optional.of(existingAttested))
        `when`(attestedKeyRepository.save(any(AttestedKeyRecord::class.java))).thenAnswer { it.getArgument(0) }

        val saved = service.registerKeyAttestation(holderId, ka)

        assertEquals(11L, saved.id)
        val captor = ArgumentCaptor.forClass(AttestedKeyRecord::class.java)
        verify(attestedKeyRepository).save(captor.capture())
        val updated = captor.value
        assertEquals(20L, updated.id)
        assertEquals(keyAlias, updated.keyAlias)
        assertEquals(AttestedKeyState.ATTESTED, updated.state)
        assertEquals(11L, updated.keyAttestation.id)
    }

    @Test
    fun `registerKeyAttestation returns existing record for same attestation id`() {
        val holderId = "demo-1"
        val walletUnit = WalletUnit(id = 1L, holderId = holderId, walletId = "w-1")
        val ka = sampleKa("key-demo-1", issuedAt = Instant.parse("2026-06-18T10:00:00Z"))
        val stableId = "${ka.keyId}:${ka.issuedAt.epochSecond}:${ka.attestedJkt}"
        val existing = KeyAttestationRecord(
            id = 5L,
            walletUnit = walletUnit,
            attestationId = stableId,
            jwt = ka.jwt,
            keyStorage = ka.keyStorage,
            statusListUri = ka.status.uri,
            statusListIndex = ka.status.index,
            technicalExpiresAt = ka.tokenExpiresAt,
            statusMaintenanceExpiresAt = ka.statusExpiresAt,
            issuedAt = ka.issuedAt,
        )

        `when`(walletUnitLifecycleService.requireIssuanceEligible(holderId)).thenReturn(walletUnit)
        `when`(keyAttestationRepository.findByAttestationId(stableId)).thenReturn(Optional.of(existing))

        val saved = service.registerKeyAttestation(holderId, ka)

        assertEquals(existing, saved)
        verify(keyAttestationRepository, never()).save(any(KeyAttestationRecord::class.java))
        verify(attestedKeyRepository, never()).save(any(AttestedKeyRecord::class.java))
    }

    private fun sampleKa(keyAlias: String, issuedAt: Instant): KeyAttestation {
        val exp = issuedAt.plusSeconds(3600)
        return KeyAttestation(
            jwt = "jwt-$issuedAt",
            keyId = keyAlias,
            keyStorage = "iso_18045_high",
            certification = "test",
            attestedJkt = "jkt-demo",
            status = KaStatusReference(listId = "ka-list", index = 3, uri = "http://localhost/status"),
            tokenExpiresAt = exp,
            statusExpiresAt = exp,
            issuedAt = issuedAt,
        )
    }
}
