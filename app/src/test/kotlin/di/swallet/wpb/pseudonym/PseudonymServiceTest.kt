/**
 * Tests pseudonym service.
 */

package di.swallet.wpb.pseudonym

import di.swallet.wpb.config.PseudonymProperties
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.transactionlog.service.TransactionLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isA
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.security.interfaces.ECPublicKey
import java.util.Optional
import java.util.UUID

class PseudonymServiceTest {
    private val properties = PseudonymProperties().apply { enabled = true; maxPerRp = 2 }
    private val repository = mock(PseudonymCredentialRepository::class.java)
    private val hsmService = mock(HsmService::class.java)
    private val transactionLogger = mock(TransactionLogger::class.java)
    private lateinit var service: PseudonymService

    /** Rebuilds PseudonymService with mocked repository, HSM, and transaction logger before each test. */
    @BeforeEach
    fun setUp() {
        service = PseudonymService(
            properties = properties,
            repository = repository,
            rpIdPolicy = RpIdPolicy(properties),
            challengeStore = PseudonymChallengeStore(),
            hsmService = hsmService,
            coseKeyMaterial = PseudonymCoseKeyMaterial(hsmService),
            transactionMapper = PseudonymTransactionMapper(properties),
            transactionLogger = transactionLogger,
        )
    }

    /**
     * Repository already reports two pseudonyms for the holder/RP pair when create is called.
     * Service must throw HTTP 409 and must not persist a new credential.
     */
    @Test
    fun `create rejects when max per rp reached`() {
        `when`(repository.countByHolderIdAndRpId("holder-1", "rp.example.com")).thenReturn(2L)
        val ex = assertThrows(ResponseStatusException::class.java) {
            service.create(CreatePseudonymRequest(holderId = "holder-1", rpId = "rp.example.com"))
        }
        assertEquals(409, ex.statusCode.value())
        verify(repository, never()).save(isA(PseudonymCredential::class.java))
    }

    /**
     * Creates two pending pseudonyms for the same holder against different RPs.
     * Both return PENDING status and persist distinct user handles.
     */
    @Test
    fun `create assigns distinct user handles per slot`() {
        val handles = mutableListOf<String>()
        `when`(repository.countByHolderIdAndRpId(anyString(), anyString())).thenReturn(0L)
        `when`(repository.save(isA(PseudonymCredential::class.java))).thenAnswer {
            val entity = it.arguments[0] as PseudonymCredential
            handles += entity.userHandle
            entity
        }
        val first = service.create(CreatePseudonymRequest("holder-1", "rp-a.example.com"))
        val second = service.create(CreatePseudonymRequest("holder-1", "rp-b.example.com"))
        assertEquals(PseudonymStatus.PENDING, first.status)
        assertEquals(PseudonymStatus.PENDING, second.status)
        assertEquals(2, handles.toSet().size)
    }

    /**
     * Pseudonym feature flag is turned off before list is called.
     * Service must throw ResponseStatusException (not found).
     */
    @Test
    fun `disabled feature returns not found`() {
        properties.enabled = false
        assertThrows(ResponseStatusException::class.java) {
            service.list("holder-1", null)
        }
    }
}
