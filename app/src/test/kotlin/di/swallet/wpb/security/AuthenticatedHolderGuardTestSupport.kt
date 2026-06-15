package di.swallet.wpb.security

import org.mockito.Mockito

object AuthenticatedHolderGuardTestSupport {
    fun noop(): AuthenticatedHolderGuard = Mockito.mock(AuthenticatedHolderGuard::class.java)

    fun noopOid4SessionAccessGuard(): Oid4SessionAccessGuard =
        Oid4SessionAccessGuard(noop())
}
