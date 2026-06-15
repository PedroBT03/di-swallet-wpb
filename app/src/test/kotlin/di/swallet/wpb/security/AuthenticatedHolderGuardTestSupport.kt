/**
 * Shared test helpers for authenticated holder guard.
 */

package di.swallet.wpb.security

import org.mockito.Mockito

object AuthenticatedHolderGuardTestSupport {
    /**
     * Returns a Mockito mock AuthenticatedHolderGuard that performs no enforcement, for
     * controller unit tests that bypass FIDO2 holder checks.
     */
    fun noop(): AuthenticatedHolderGuard = Mockito.mock(AuthenticatedHolderGuard::class.java)

    /**
     * Returns an Oid4SessionAccessGuard wired to the noop holder guard so OID4 session
     * endpoints can be exercised without real authentication in controller tests.
     */
    fun noopOid4SessionAccessGuard(): Oid4SessionAccessGuard =
        Oid4SessionAccessGuard(noop())
}
