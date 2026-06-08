package di.swallet.wpb.service

import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.domain.StatusList
import di.swallet.wpb.domain.StatusListRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

class StatusListServiceTest {

    private val repository = mock(StatusListRepository::class.java)
    private val properties = StatusListProperties(capacity = 1024)
    private lateinit var statusListService: StatusListService

    @BeforeEach
    fun setup() {
        statusListService = StatusListService(repository, properties)

        val mockList = StatusList(
            id = "PRIMARY_LIST",
            bitstring = ByteArray(0),
            nextIndex = 0,
            capacity = 1024,
            allocatedBitstring = ByteArray(0),
        )
        `when`(repository.findById("PRIMARY_LIST")).thenReturn(Optional.of(mockList))

        statusListService.init()
    }

    @Test
    fun `should allocate random indices within capacity`() {
        val firstIndex = statusListService.allocateRandomIndex()
        val secondIndex = statusListService.allocateRandomIndex()

        assertTrue(firstIndex in 0 until 1024)
        assertTrue(secondIndex in 0 until 1024)
        assertNotEquals(firstIndex, secondIndex)
        assertTrue(statusListService.isAllocated(firstIndex))
    }

    @Test
    fun `should correctly set and check revocation bit`() {
        val index = statusListService.allocateRandomIndex()

        assertFalse(statusListService.isRevoked(index))

        statusListService.revoke(index)

        assertTrue(statusListService.isRevoked(index))
    }
}
