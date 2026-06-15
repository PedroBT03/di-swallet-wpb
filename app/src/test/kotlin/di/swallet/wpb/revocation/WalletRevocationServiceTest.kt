/**
 * Tests wallet revocation service.
 */

package di.swallet.wpb.revocation

import di.swallet.wpb.domain.CredentialRevocationState
import di.swallet.wpb.domain.KeyAttestationRecordRepository
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.domain.WalletUnitState
import di.swallet.wpb.ka.status.KaStatusManagementService
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.StatusListService
import di.swallet.wpb.service.WalletUnitLifecycleService
import di.swallet.wpb.wia.status.WiaStatusManagementService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class WalletRevocationServiceTest {

    private val walletUnitRepository = Mockito.mock(WalletUnitRepository::class.java)
    private val walletUnitLifecycleService = Mockito.mock(WalletUnitLifecycleService::class.java)
    private val wiaStatusManagementService = Mockito.mock(WiaStatusManagementService::class.java)
    private val kaStatusManagementService = Mockito.mock(KaStatusManagementService::class.java)
    private val keyAttestationRepository = Mockito.mock(KeyAttestationRecordRepository::class.java)
    private val walletKeyRepository = Mockito.mock(WalletKeyRepository::class.java)
    private val walletCredentialRepository = Mockito.mock(WalletCredentialRepository::class.java)
    private val statusListService = Mockito.mock(StatusListService::class.java)
    private val credentialRevocationGuard = Mockito.mock(CredentialRevocationGuard::class.java)
    private val hsmService = Mockito.mock(HsmService::class.java)

    private val service = WalletRevocationService(
        walletUnitRepository = walletUnitRepository,
        walletUnitLifecycleService = walletUnitLifecycleService,
        wiaStatusManagementService = wiaStatusManagementService,
        kaStatusManagementService = kaStatusManagementService,
        keyAttestationRepository = keyAttestationRepository,
        walletKeyRepository = walletKeyRepository,
        walletCredentialRepository = walletCredentialRepository,
        statusListService = statusListService,
        credentialRevocationGuard = credentialRevocationGuard,
        hsmService = hsmService,
    )

    /**
     * Revokes a valid wallet unit that has a holder key alias and a status-list revocation index.
     * Service must revoke the status-list entry, delete the HSM wallet key, and call wallet unit lifecycle revoke.
     */
    @Test
    fun `revokeWalletUnit deletes holder HSM key after status revocation`() {
        val walletUnit = WalletUnit(id = 1L, walletId = "wallet-1", holderId = "holder-1", state = WalletUnitState.VALID)
        val walletKey = WalletKey(userId = "holder-1", keyAlias = "key-holder-1-123", revocationIndex = 7)
        Mockito.`when`(walletUnitRepository.findByWalletId("wallet-1")).thenReturn(Optional.of(walletUnit))
        Mockito.`when`(walletKeyRepository.findByUserId("holder-1")).thenReturn(Optional.of(walletKey))
        Mockito.`when`(walletCredentialRepository.findByUserId("holder-1")).thenReturn(emptyList())
        Mockito.`when`(keyAttestationRepository.findByWalletUnitId(1L)).thenReturn(emptyList())

        service.revokeWalletUnit("wallet-1")

        Mockito.verify(statusListService).revoke(7)
        Mockito.verify(hsmService).deleteWalletKey("key-holder-1-123")
        Mockito.verify(walletUnitLifecycleService).revoke(walletUnit)
    }

    /**
     * Revokes a WP-managed credential that carries status list index 3.
     * statusListService.revoke(3) must be called and the saved credential must have REVOKED state.
     */
    @Test
    fun `revokeCredential marks WP-managed credential revoked`() {
        val credential = WalletCredential(
            id = 42L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "sd-jwt",
            encryptedDisclosures = "enc",
            statusListIndex = 3,
        )
        Mockito.`when`(walletCredentialRepository.findById(42L)).thenReturn(Optional.of(credential))
        Mockito.`when`(credentialRevocationGuard.isWpManaged(credential)).thenReturn(true)

        service.revokeCredential(42L)

        Mockito.verify(statusListService).revoke(3)
        Mockito.verify(walletCredentialRepository).save(
            Mockito.argThat { it.revocationState == CredentialRevocationState.REVOKED },
        )
    }
}
