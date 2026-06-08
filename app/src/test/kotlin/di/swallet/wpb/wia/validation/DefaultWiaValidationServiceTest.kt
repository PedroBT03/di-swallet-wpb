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
    fun setup() {
        service = DefaultWiaValidationService(statusListService)
    }

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

    @Test
    fun `valid attestation passes technical validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(false)
        assertDoesNotThrow { service.validateTechnical(attestation()) }
    }

    @Test
    fun `revoked client status fails validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(true)
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateTechnical(attestation())
        }
        assertEquals("wia_revoked", ex.code)
    }

    @Test
    fun `expired WIA token fails validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(false)
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateTechnical(attestation(tokenExpOffsetSeconds = -1))
        }
        assertEquals("wia_expired", ex.code)
    }

    @Test
    fun `expired client status fails validation`() {
        `when`(statusListService.isRevoked(42)).thenReturn(false)
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateTechnical(attestation(statusExpOffsetSeconds = -1))
        }
        assertEquals("wia_status_expired", ex.code)
    }

    @Test
    fun `matching jkt passes binding validation`() {
        assertDoesNotThrow { service.validateAccessTokenBinding("jkt-1", "jkt-1") }
    }

    @Test
    fun `mismatch jkt fails binding validation`() {
        val ex = assertThrows(WiaValidationException::class.java) {
            service.validateAccessTokenBinding("jkt-1", "jkt-2")
        }
        assertEquals("wia_binding_mismatch", ex.code)
    }
}
