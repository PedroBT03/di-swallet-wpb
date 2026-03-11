package di.swallet.wpb.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StatusListServiceTest {

    private val statusListService = StatusListService()

    /**
     * Verifies that the service correctly allocates sequential indices.
     */
    @Test
    fun `should allocate sequential indices`() {
        val firstIndex = statusListService.getNextRevocationIndex()
        val secondIndex = statusListService.getNextRevocationIndex()
        
        assertEquals(0, firstIndex)
        assertEquals(1, secondIndex)
    }

    /**
     * Verifies that a bit can be set to 1 (revoked) and correctly checked.
     */
    @Test
    fun `should correctly set and check revocation bit`() {
        val index = 5
        
        // Initial state should be Active (false/0)
        assertFalse(statusListService.isRevoked(index))
        
        // Revoke the index
        statusListService.revoke(index)
        
        // State should now be Revoked (true/1)
        assertTrue(statusListService.isRevoked(index))
    }
}