/**
 * Tests wsca access guard.
 */

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

    /**
     * Mocks the authenticated FIDO2 holder as holder-1 and calls requireSciForHolder with
     * the same id, expecting the guard to allow the HSM operation without throwing.
     */
    @Test
    fun `allows HSM operation when FIDO2 holder matches`() {
        Mockito.`when`(holderContext.currentHolderId()).thenReturn("holder-1")
        assertDoesNotThrow { guard.requireSciForHolder("holder-1") }
    }

    /**
     * Authenticates as holder-1 but requests SCI access for holder-2 and expects a
     * ResponseStatusException because the path holder does not match the session.
     */
    @Test
    fun `blocks HSM operation for different holder`() {
        Mockito.`when`(holderContext.currentHolderId()).thenReturn("holder-1")
        assertThrows(ResponseStatusException::class.java) {
            guard.requireSciForHolder("holder-2")
        }
    }

    /**
     * Grants an active consent SCI grant for holder-1 without a matching FIDO2 session and
     * expects requireSciForHolder to succeed based on the grant alone.
     */
    @Test
    fun `allows HSM operation with active consent grant`() {
        grantService.grant("holder-1")
        assertDoesNotThrow { guard.requireSciForHolder("holder-1") }
    }
}
