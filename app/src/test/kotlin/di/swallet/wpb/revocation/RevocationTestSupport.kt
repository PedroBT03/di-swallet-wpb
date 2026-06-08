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

    fun credentialStatusParser(): CredentialStatusParser =
        CredentialStatusParser(ObjectMapper())

    fun statusListProperties(capacity: Int = 1024): StatusListProperties =
        StatusListProperties().apply { this.capacity = capacity }
}
