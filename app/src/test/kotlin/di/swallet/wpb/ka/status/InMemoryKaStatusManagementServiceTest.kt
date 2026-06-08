package di.swallet.wpb.ka.status

import di.swallet.wpb.revocation.RevocationTestSupport
import di.swallet.wpb.service.StatusListService
import di.swallet.wpb.domain.StatusList
import di.swallet.wpb.domain.StatusListRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

class InMemoryKaStatusManagementServiceTest {
    private val repository = mock(StatusListRepository::class.java)
    private lateinit var statusListService: StatusListService
    private lateinit var service: InMemoryKaStatusManagementService

    @BeforeEach
    fun setup() {
        statusListService = StatusListService(repository, RevocationTestSupport.statusListProperties())
        val mockList = StatusList(
            id = "PRIMARY_LIST",
            bitstring = ByteArray(10),
            nextIndex = 0,
            capacity = 1024,
            allocatedBitstring = ByteArray(0),
        )
        `when`(repository.findById("PRIMARY_LIST")).thenReturn(Optional.of(mockList))
        statusListService.init()
        service = InMemoryKaStatusManagementService(statusListService)
    }

    @Test
    fun `same holder issuer and attestation reuses index`() {
        val a = service.getOrAllocateStatus("holder-1", "issuer-a", "fp-1")
        val b = service.getOrAllocateStatus("holder-1", "issuer-a", "fp-1")
        assertEquals(a.index, b.index)
    }

    @Test
    fun `different attestation fingerprint allocates new index`() {
        val a = service.getOrAllocateStatus("holder-1", "issuer-a", "fp-1")
        val b = service.getOrAllocateStatus("holder-1", "issuer-a", "fp-2")
        assertNotEquals(a.index, b.index)
    }
}
