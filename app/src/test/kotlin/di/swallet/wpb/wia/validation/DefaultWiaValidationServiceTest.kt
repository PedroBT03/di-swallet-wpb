/**
 * Tests WIA technical validation and access-token binding rules.
 */

package di.swallet.wpb.wia.validation

import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
import di.swallet.wpb.issuance.domain.WiaStatusReference
import di.swallet.wpb.service.StatusListService
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class DefaultWiaValidationServiceTest {

    private val statusListService = mock(StatusListService::class.java)
    private lateinit var service: DefaultWiaValidationService

    @BeforeEach
    /** Constructs DefaultWiaValidationService backed by the mocked status list service. */
    fun setup() {
        service = DefaultWiaValidationService(statusListService)
    }

    /** Synthetic WalletInstanceAttestation with adjustable expiry offsets, cnfJkt, and status index. */
    private fun attestation(
        tokenExpOffsetSeconds: Long = 3600,
        statusExpOffsetSeconds: Long = 31L * 24 * 3600,
        cnfJkt: String = "jkt-1",
        statusIndex: Int = 42,
    ): WalletInstanceAttestation {
        val now = Instant.now()
        return WalletInstanceAttestation(
            jwt = "wia.jwt",
            popJwt = "wia.pop",
            walletInstanceId = "holder-1",
            walletName = "wallet",
            walletVersion = "1",
            walletSolutionCertificationInformation = "cert",
            cnfJkt = cnfJkt,
            clientStatus = WiaStatusReference("PRIMARY_LIST", statusIndex, "/api/v1/wallet/status-lists/PRIMARY_LIST"),
            tokenExpiresAt = now.plusSeconds(tokenExpOffsetSeconds),
            clientStatusExpiresAt = now.plusSeconds(statusExpOffsetSeconds),
            issuedAt = now,
        )
    }

    /**
     * Non-revoked status index and unexpired token/status timestamps pass validateTechnical without exception.
     */
    @Test
    fun `valid attestation passes technical validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(false)
        assertDoesNotThrow { service.validateTechnical(attestation()) }
    }

    /**
     * StatusListService reports revoked for the attestation index; validateTechnical throws wia_revoked.
     */
    @Test
    fun `revoked client status fails validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(true)
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateTechnical(attestation())
        }
        assertEquals("wia_revoked", ex.code)
    }

    /**
     * tokenExpiresAt in the past; validateTechnical throws wia_expired.
     */
    @Test
    fun `expired WIA token fails validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(false)
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateTechnical(attestation(tokenExpOffsetSeconds = -1))
        }
        assertEquals("wia_expired", ex.code)
    }

    /**
     * clientStatusExpiresAt in the past; validateTechnical throws wia_status_expired.
     */
    @Test
    fun `expired client status fails validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(false)
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateTechnical(attestation(statusExpOffsetSeconds = -1))
        }
        assertEquals("wia_status_expired", ex.code)
    }

    /**
     * Matching cnf jkt values pass validateAccessTokenBinding without exception.
     */
    @Test
    fun `matching jkt passes binding validation`() {
        assertDoesNotThrow { service.validateAccessTokenBinding("jkt-1", "jkt-1") }
    }

    /**
     * Mismatched cnf jkt values throw wia_binding_mismatch.
     */
    @Test
    fun `mismatch jkt fails binding validation`() {
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateAccessTokenBinding("jkt-1", "jkt-2")
        }
        assertEquals("wia_binding_mismatch", ex.code)
    }
}
