package di.swallet.wpb.ka.status

import di.swallet.wpb.domain.KaStatusIndex
import di.swallet.wpb.domain.KaStatusIndexRepository
import di.swallet.wpb.service.StatusListService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class JpaKaStatusManagementServiceTest {

    private lateinit var repository: KaStatusIndexRepository
    private lateinit var statusListService: StatusListService
    private lateinit var service: JpaKaStatusManagementService

    @BeforeEach
    fun setUp() {
        repository = mock(KaStatusIndexRepository::class.java)
        statusListService = mock(StatusListService::class.java)
        `when`(statusListService.getListId()).thenReturn("PRIMARY_LIST")
        service = JpaKaStatusManagementService(repository, statusListService)
    }

    @Test
    fun `reuses persisted index for same holder issuer and attestation fingerprint`() {
        `when`(statusListService.getNextRevocationIndex()).thenReturn(31)
        `when`(
            repository.findByHolderIdAndIssuerScopeAndAttestationFingerprint(
                "holder-1",
                "issuer-a",
                "fp-abc",
            ),
        ).thenReturn(java.util.Optional.empty(), persisted("holder-1", "issuer-a", "fp-abc", 31))
        `when`(repository.save(org.mockito.ArgumentMatchers.any()))
            .thenAnswer { invocation -> invocation.arguments[0] }

        val first = service.getOrAllocateStatus("holder-1", "issuer-a", "fp-abc")
        val second = service.getOrAllocateStatus("holder-1", "issuer-a", "fp-abc")

        assertEquals(31, first.index)
        assertEquals(31, second.index)
        verify(statusListService, times(1)).getNextRevocationIndex()
    }

    @Test
    fun `revoke holder revokes all persisted indexes`() {
        `when`(repository.findAllByHolderId("holder-2")).thenReturn(
            listOf(
                persistedEntity("holder-2", "issuer-a", "fp-1", 11),
                persistedEntity("holder-2", "issuer-b", "fp-2", 12),
            ),
        )

        service.revokeHolder("holder-2")

        verify(statusListService).revoke(11)
        verify(statusListService).revoke(12)
    }

    private fun persisted(holderId: String, issuerScope: String, fingerprint: String, index: Int) =
        java.util.Optional.of(persistedEntity(holderId, issuerScope, fingerprint, index))

    private fun persistedEntity(holderId: String, issuerScope: String, fingerprint: String, index: Int) =
        KaStatusIndex(
            holderId = holderId,
            issuerScope = issuerScope,
            attestationFingerprint = fingerprint,
            listId = "PRIMARY_LIST",
            statusIndex = index,
        )
}
