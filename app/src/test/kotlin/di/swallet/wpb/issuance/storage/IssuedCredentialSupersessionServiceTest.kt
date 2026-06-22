/**
 * Tests issued credential supersession on re-issue.
 */

package di.swallet.wpb.issuance.storage

import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.domain.CredentialRevocationState
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.revocation.CredentialRevocationGuard
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.StatusListService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class IssuedCredentialSupersessionServiceTest {

    @Test
    fun `revokes prior active mDL when issuing another mDL`() {
        val existing = WalletCredential(
            id = 1L,
            userId = "holder-1",
            credentialType = "MDL",
            encodedData = "old",
            encryptedDisclosures = "",
            statusListId = "WP_LIST",
            statusListIndex = 7,
        )
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(existing))
        `when`(repository.save(existing)).thenAnswer { it.getArgument<WalletCredential>(0) }

        val statusListService = mock(StatusListService::class.java)
        `when`(statusListService.isRevoked(anyInt())).thenReturn(false)
        val guard = CredentialRevocationGuard(repository, statusListService, mock(HsmService::class.java), StatusListProperties())
        val service = IssuedCredentialSupersessionService(repository, guard, statusListService)

        service.supersedeActiveOfSameFamily("holder-1", "MDL")

        verify(statusListService).revoke(7)
        assertEquals(CredentialRevocationState.REVOKED, existing.revocationState)
    }

    @Test
    fun `does not revoke pid when issuing mDL`() {
        val pid = WalletCredential(
            id = 2L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "pid",
            encryptedDisclosures = "enc",
        )
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findByUserId("holder-1")).thenReturn(listOf(pid))

        val statusListService = mock(StatusListService::class.java)
        `when`(statusListService.isRevoked(anyInt())).thenReturn(false)
        val guard = CredentialRevocationGuard(repository, statusListService, mock(HsmService::class.java), StatusListProperties())
        val service = IssuedCredentialSupersessionService(repository, guard, statusListService)

        service.supersedeActiveOfSameFamily("holder-1", "MDL")

        verify(repository, never()).save(pid)
        verify(statusListService, never()).revoke(anyInt())
        assertEquals(CredentialRevocationState.ACTIVE, pid.revocationState)
    }
}
