/**
 * Tests wallet unit lifecycle.
 */

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
import org.junit.jupiter.api.Assertions.assertNotNull
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

    /**
     * initWallet provisions an anonymous CANDIDATE wallet unit and issues the WUA (WIA + KA).
     */
    @Test
    fun `init provisions candidate wallet with wua`() {
        val holderId = "lifecycle-${UUID.randomUUID()}"
        val result = deviceBindingService.initWallet(
            WalletInitCommand(
                holderId = holderId,
                platform = "android",
                devicePubJwk = WalletTestSupport.ecPublicJwk(),
            ),
        )
        assertEquals(WalletUnitState.CANDIDATE, result.state)
        assertNotNull(result.wiaJwt)
        assertNotNull(result.kaJwt)
        val wallet = walletUnitRepository.findFirstByHolderId(holderId).orElseThrow()
        assertEquals(WalletUnitState.CANDIDATE, wallet.state)
    }

    /**
     * requireIssuanceEligible for a holder without a provisioned wallet throws ResponseStatusException.
     */
    @Test
    fun `issuance requires provisioned wallet`() {
        val holderId = "not-init-${UUID.randomUUID()}"
        assertThrows(ResponseStatusException::class.java) {
            walletUnitLifecycleService.requireIssuanceEligible(holderId)
        }
    }

  /**
   * requireSignCapable rejects CANDIDATE wallets until identity activation.
   */
    @Test
    fun `holder sign requires valid wallet`() {
        val holderId = "sign-eligible-${UUID.randomUUID()}"
        deviceBindingService.initWallet(
            WalletInitCommand(holderId = holderId, platform = "web", devicePubJwk = WalletTestSupport.ecPublicJwk()),
        )
        assertThrows(ResponseStatusException::class.java) {
            walletUnitLifecycleService.requireSignCapable(holderId)
        }
        val wallet = walletUnitRepository.findFirstByHolderId(holderId).orElseThrow()
        walletUnitLifecycleService.markValid(wallet)
        val eligible = walletUnitLifecycleService.requireSignCapable(holderId)
        assertEquals(WalletUnitState.VALID, eligible.state)
    }

    /**
     * Device binding stores RFC 7638 thumbprint derived from the submitted devicePubJwk.
     */
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
