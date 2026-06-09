package di.swallet.wpb.revocation

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.wallet.WalletTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.UUID

@ConformanceTest
class WalletCredentialRevocationTest : BaseIntegrationTest() {

    @Autowired lateinit var deviceBindingService: DeviceBindingService
    @Autowired lateinit var walletUnitRepository: WalletUnitRepository
    @Autowired lateinit var hsmService: HsmService

    @Test
    @ConformanceScenario("credential_revocation_blocks_use")
    fun `revoked WP-managed credential is rejected for presentation`() {
        val userId = "revoke-cred-${UUID.randomUUID()}"
        WalletTestSupport.bootstrapHolderForIssuance(
            deviceBindingService,
            walletUnitRepository,
            hsmService,
            userId,
        )

        val issueResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/issue-sd/$userId",
            HttpEntity<String>(getDynamicHeaders(userId)),
            WalletCredential::class.java,
        )
        assertThat(issueResponse.statusCode).isEqualTo(HttpStatus.OK)
        val credentialId = issueResponse.body?.id ?: error("missing credential id")
        assertThat(issueResponse.body?.statusListIndex).isNotNull

        val revokeResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/$credentialId/revoke",
            HttpEntity<String>(getDynamicHeaders(userId)),
            Map::class.java,
        )
        assertThat(revokeResponse.statusCode).isEqualTo(HttpStatus.OK)

        val presentationResponse = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/$credentialId/presentation",
            HttpEntity(
                mapOf("claimsToDisclose" to listOf("given_name")),
                getDynamicHeaders(userId),
            ),
            Map::class.java,
        )
        assertThat(presentationResponse.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
