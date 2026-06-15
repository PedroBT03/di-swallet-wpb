/**
 * Shared test helpers for revocation.
 */

package di.swallet.wpb.revocation

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.StatusListService
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

object RevocationTestSupport {
    /** CredentialRevocationGuard backed by mocks that never report revoked status-list entries. */
    fun noopGuard(): CredentialRevocationGuard {
        val statusListService = mock(StatusListService::class.java)
        `when`(statusListService.isRevoked(anyInt())).thenReturn(false)
        return CredentialRevocationGuard(
            walletCredentialRepository = mock(WalletCredentialRepository::class.java),
            statusListService = statusListService,
            hsmService = mock(HsmService::class.java),
            properties = StatusListProperties(),
        )
    }

    /** CredentialStatusParser using a default ObjectMapper for parsing status claims in tests. */
    fun credentialStatusParser(): CredentialStatusParser =
        CredentialStatusParser(ObjectMapper())

    /** StatusListProperties preconfigured with the given token capacity for revocation tests. */
    fun statusListProperties(capacity: Int = 1024): StatusListProperties =
        StatusListProperties().apply { this.capacity = capacity }
}
