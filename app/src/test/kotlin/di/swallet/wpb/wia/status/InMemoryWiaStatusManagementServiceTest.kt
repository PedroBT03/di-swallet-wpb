/**
 * Tests in memory wia status management service.
 */

package di.swallet.wpb.wia.status

import di.swallet.wpb.service.StatusListService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class InMemoryWiaStatusManagementServiceTest {

    /**
     * Same holder-issuer pair reuses index 7; a different issuer under the same holder gets index 8.
     */
    @Test
    fun `allocates and reuses index per holder-issuer key`() {
        val status = mock(StatusListService::class.java)
        `when`(status.getListId()).thenReturn("PRIMARY_LIST")
        `when`(status.getNextRevocationIndex()).thenReturn(7, 8, 9)

        val service = InMemoryWiaStatusManagementService(status)
        val a = service.getOrAllocateStatus("h1", "issuer-a")
        val b = service.getOrAllocateStatus("h1", "issuer-a")
        val c = service.getOrAllocateStatus("h1", "issuer-b")

        assertEquals(7, a.index)
        assertEquals(7, b.index)
        assertEquals(8, c.index)
    }

    /**
     * Two issuer scopes allocated for holder h2; revokeHolder revokes both indices 11 and 12.
     */
    @Test
    fun `revoke holder revokes all allocated indexes`() {
        val status = mock(StatusListService::class.java)
        `when`(status.getListId()).thenReturn("PRIMARY_LIST")
        `when`(status.getNextRevocationIndex()).thenReturn(11, 12)

        val service = InMemoryWiaStatusManagementService(status)
        service.getOrAllocateStatus("h2", "issuer-a")
        service.getOrAllocateStatus("h2", "issuer-b")
        service.revokeHolder("h2")

        org.mockito.Mockito.verify(status).revoke(11)
        org.mockito.Mockito.verify(status).revoke(12)
        assertTrue(true)
    }
}
