package di.swallet.wpb.security

import di.swallet.wpb.config.WscaSciProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.server.ResponseStatusException

class WscaAccessGuardTest {
    private val properties = WscaSciProperties().apply { enforceSciBoundary = true }
    private val holderContext = Mockito.mock(AuthenticatedHolderContext::class.java)
    private val grantService = WscaSciGrantService(properties)
    private val guard = WscaAccessGuard(
        properties = properties,
        authenticatedHolderContext = holderContext,
        sciGrantService = grantService,
        walletKeyRepository = Mockito.mock(di.swallet.wpb.domain.WalletKeyRepository::class.java),
        pseudonymCredentialRepository = Mockito.mock(di.swallet.wpb.pseudonym.PseudonymCredentialRepository::class.java),
    )

    @Test
    fun `allows HSM operation when FIDO2 holder matches`() {
        Mockito.`when`(holderContext.currentHolderId()).thenReturn("holder-1")
        assertDoesNotThrow { guard.requireSciForHolder("holder-1") }
    }

    @Test
    fun `blocks HSM operation for different holder`() {
        Mockito.`when`(holderContext.currentHolderId()).thenReturn("holder-1")
        assertThrows(ResponseStatusException::class.java) {
            guard.requireSciForHolder("holder-2")
        }
    }

    @Test
    fun `allows HSM operation with active consent grant`() {
        grantService.grant("holder-1")
        assertDoesNotThrow { guard.requireSciForHolder("holder-1") }
    }
}
