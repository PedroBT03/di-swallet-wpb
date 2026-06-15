/**
 * Tests jpa wia status management service.
 */

package di.swallet.wpb.wia.status

import di.swallet.wpb.domain.WiaStatusIndexRepository
import di.swallet.wpb.service.StatusListService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify

class JpaWiaStatusManagementServiceTest {

    private lateinit var repository: WiaStatusIndexRepository
    private lateinit var statusListService: StatusListService
    private lateinit var service: JpaWiaStatusManagementService

    @BeforeEach
    /** Wires mocked repository and status list collaborators into JpaWiaStatusManagementService. */
    fun setUp() {
        repository = mock(WiaStatusIndexRepository::class.java)
        statusListService = mock(StatusListService::class.java)
        `when`(statusListService.getListId()).thenReturn("PRIMARY_LIST")
        service = JpaWiaStatusManagementService(repository, statusListService)
    }

    /**
     * First call persists index 21; second lookup reuses it and getNextRevocationIndex runs only once.
     */
    @Test
    fun `reuses persisted index for same holder and issuer scope`() {
        `when`(statusListService.getNextRevocationIndex()).thenReturn(21)
        `when`(repository.findByHolderIdAndIssuerScope("holder-1", "issuer-a"))
            .thenReturn(java.util.Optional.empty(), persisted("holder-1", "issuer-a", 21))
        `when`(repository.save(org.mockito.ArgumentMatchers.any()))
            .thenAnswer { invocation -> invocation.arguments[0] }

        val first = service.getOrAllocateStatus("holder-1", "issuer-a")
        val second = service.getOrAllocateStatus("holder-1", "issuer-a")

        assertEquals(21, first.index)
        assertEquals(21, second.index)
        verify(statusListService, org.mockito.Mockito.times(1)).getNextRevocationIndex()
    }

    /**
     * revokeHolder for holder-2 calls statusListService.revoke for each persisted WIA index.
     */
    @Test
    fun `revoke holder revokes all persisted indexes`() {
        `when`(repository.findAllByHolderId("holder-2")).thenReturn(
            listOf(
                persistedEntity("holder-2", "issuer-a", 11),
                persistedEntity("holder-2", "issuer-b", 12),
            ),
        )

        service.revokeHolder("holder-2")

        verify(statusListService).revoke(11)
        verify(statusListService).revoke(12)
    }

    /** Wraps a persisted WiaStatusIndex entity in an Optional for repository stub return values. */
    private fun persisted(holderId: String, issuerScope: String, index: Int) =
        java.util.Optional.of(persistedEntity(holderId, issuerScope, index))

    /** Builds a WiaStatusIndex row for the given holder, issuer scope, and status index. */
    private fun persistedEntity(holderId: String, issuerScope: String, index: Int) =
        di.swallet.wpb.domain.WiaStatusIndex(
            holderId = holderId,
            issuerScope = issuerScope,
            listId = "PRIMARY_LIST",
            statusIndex = index,
        )
}
