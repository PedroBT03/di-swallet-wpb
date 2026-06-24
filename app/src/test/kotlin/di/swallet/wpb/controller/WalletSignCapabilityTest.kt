/**
 * Holder-facing HSM sign requires a VALID wallet unit.
 */

package di.swallet.wpb.controller

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.WalletUnitLifecycleService
import di.swallet.wpb.wallet.WalletTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.UUID

class WalletSignCapabilityTest : BaseIntegrationTest() {

    @Autowired
    lateinit var deviceBindingService: DeviceBindingService

    @Autowired
    lateinit var walletUnitRepository: WalletUnitRepository

    @Autowired
    lateinit var walletUnitLifecycleService: WalletUnitLifecycleService

    @Test
    fun `holder sign is blocked while wallet is CANDIDATE`() {
        val holderId = "sign-candidate-${UUID.randomUUID()}"
        getDynamicHeaders(holderId)
        WalletTestSupport.initOperationalWallet(deviceBindingService, holderId)

        val blocked = restTemplate.postForEntity(
            "/api/v1/wallet/sign/$holderId",
            HttpEntity(mapOf("data" to "blocked"), getDynamicHeaders(holderId)),
            Map::class.java,
        )
        assertThat(blocked.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `holder sign succeeds when wallet is VALID`() {
        val holderId = "sign-valid-${UUID.randomUUID()}"
        getDynamicHeaders(holderId)
        WalletTestSupport.initOperationalWallet(deviceBindingService, holderId)
        WalletTestSupport.markHolderWalletValid(
            walletUnitRepository,
            walletUnitLifecycleService,
            holderId,
        )

        val signResponse = restTemplate.postForEntity(
            "/api/v1/wallet/sign/$holderId",
            HttpEntity(mapOf("data" to "allowed"), getDynamicHeaders(holderId)),
            Map::class.java,
        )
        assertThat(signResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signResponse.body?.get("signature")).isNotNull
    }
}
