package di.swallet.wpb.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import di.swallet.wpb.domain.StatusListRepository
import di.swallet.wpb.domain.StatusList
import java.util.Optional


class StatusListServiceTest {

    private val repository = mock(StatusListRepository::class.java)
    private lateinit var statusListService: StatusListService

    @BeforeEach
    fun setup() {
        statusListService = StatusListService(repository)
        
        // Mock the initial state for the unit test
        val mockList = StatusList(id = "PRIMARY_LIST", bitstring = ByteArray(10), nextIndex = 0)
        `when`(repository.findById("PRIMARY_LIST")).thenReturn(Optional.of(mockList))
        
        // Manually trigger the init that Spring would normally call
        statusListService.init()
    }

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