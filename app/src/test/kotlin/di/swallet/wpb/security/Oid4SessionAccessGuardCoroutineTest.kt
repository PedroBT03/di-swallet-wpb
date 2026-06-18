/**
 * Verifies holder guards work when RequestContextHolder is unavailable (suspend MVC).
 */

package di.swallet.wpb.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder

class Oid4SessionAccessGuardCoroutineTest {

    private val holderContext = AuthenticatedHolderContext()

    @BeforeEach
    fun clearRequestContext() {
        RequestContextHolder.resetRequestAttributes()
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
        AuthenticatedHolderRequestBinder.clear()
    }

    /**
     * Suspend controllers resume off the servlet thread; guards must read the holder
     * from RequestContextHolder (bound in the FIDO2 interceptor) or an explicit request.
     */
    @Test
    fun `requireCurrentHolderId uses explicit servlet request when thread context is missing`() = runBlocking(Dispatchers.Default) {
        val servletRequest = MockHttpServletRequest()
        servletRequest.setAttribute(WalletSecurityAttributes.AUTHENTICATED_HOLDER_ID, "demo-1")

        assertEquals("demo-1", holderContext.requireCurrentHolderId(servletRequest))
    }

    @Test
    fun `requireCurrentHolderId reads servlet thread binding`() = runBlocking(Dispatchers.Default) {
        try {
            AuthenticatedHolderRequestBinder.bind("demo-1")
            assertEquals("demo-1", holderContext.requireCurrentHolderId())
        } finally {
            AuthenticatedHolderRequestBinder.clear()
        }
    }

    @Test
    fun `requireCurrentHolderId prefers RequestContextHolder over empty explicit request`() {
        val servletRequest = MockHttpServletRequest()
        servletRequest.setAttribute(WalletSecurityAttributes.AUTHENTICATED_HOLDER_ID, "demo-1")
        RequestContextHolder.setRequestAttributes(org.springframework.web.context.request.ServletRequestAttributes(servletRequest))

        val emptyWrapper = MockHttpServletRequest()
        assertEquals("demo-1", holderContext.requireCurrentHolderId(emptyWrapper))
    }
}
