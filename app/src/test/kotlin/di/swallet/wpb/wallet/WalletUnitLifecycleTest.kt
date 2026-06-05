package di.swallet.wpb.wallet

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.domain.DeviceWalletBindingRepository
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.domain.WalletUnitState
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.WalletInitCommand
import di.swallet.wpb.service.WalletUnitLifecycleService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class WalletUnitLifecycleTest : BaseIntegrationTest() {

    @Autowired
    lateinit var deviceBindingService: DeviceBindingService

    @Autowired
    lateinit var walletUnitLifecycleService: WalletUnitLifecycleService

    @Autowired
    lateinit var walletUnitRepository: WalletUnitRepository

    @Autowired
    lateinit var deviceWalletBindingRepository: DeviceWalletBindingRepository

    @Test
    fun `init creates candidate then activates to operational`() {
        val holderId = "lifecycle-${UUID.randomUUID()}"
        val result = deviceBindingService.initWallet(
            WalletInitCommand(
                holderId = holderId,
                platform = "android",
                devicePubJwk = WalletTestSupport.ecPublicJwk(),
            ),
        )
        assertEquals(WalletUnitState.OPERATIONAL, result.state)
        val wallet = walletUnitRepository.findFirstByHolderId(holderId).orElseThrow()
        assertEquals(WalletUnitState.OPERATIONAL, wallet.state)
    }

    @Test
    fun `issuance requires operational wallet`() {
        val holderId = "not-init-${UUID.randomUUID()}"
        assertThrows(ResponseStatusException::class.java) {
            walletUnitLifecycleService.requireIssuanceEligible(holderId)
        }
    }

    @Test
    fun `device binding uses RFC 7638 thumbprints`() {
        val jwk = WalletTestSupport.ecPublicJwk()
        val holderId = "thumb-${UUID.randomUUID()}"
        deviceBindingService.initWallet(
            WalletInitCommand(holderId = holderId, platform = "ios", devicePubJwk = jwk),
        )
        val expected = Rfc7638JwkThumbprint.fromJwkJson(jwk)
        val wallet = walletUnitRepository.findFirstByHolderId(holderId).orElseThrow()
        val binding = deviceWalletBindingRepository.findByWalletUnitId(wallet.id!!).first()
        assertEquals(expected, binding.deviceKeyThumbprint)
    }
}
