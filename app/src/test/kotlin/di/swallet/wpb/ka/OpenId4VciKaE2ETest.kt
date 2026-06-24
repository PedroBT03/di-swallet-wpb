/**
 * End-to-end tests for open id4 vci ka.
 */

package di.swallet.wpb.ka

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import di.swallet.wpb.consent.IssuanceConsentTestSupport
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.domain.KaState
import di.swallet.wpb.issuance.orchestration.IssuanceFlowOrchestrator
import di.swallet.wpb.observability.IssuanceEventStore
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.wallet.WalletTestSupport
import di.swallet.wpb.wia.validation.WiaPopTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

@ConformanceTest
class OpenId4VciKaE2ETest : BaseIntegrationTest() {

    @Autowired
    lateinit var orchestrator: IssuanceFlowOrchestrator

    @Autowired
    lateinit var eventStore: IssuanceEventStore

    @Autowired
    lateinit var hsmService: HsmService

    @Autowired
    lateinit var deviceBindingService: DeviceBindingService

    @Autowired
    lateinit var walletUnitRepository: WalletUnitRepository

    /**
     * Bootstrapped holder completes pid_jwt issuance; KA reaches VALIDATED and ka.generated/attached/validated events are recorded.
     */
    @Test
    @ConformanceScenario("vci_ka_binding_e2e")
    fun `device bound issuance performs KA generation attachment and validation`() {
        val holderId = "ka-e2e-${UUID.randomUUID()}"
        val deviceKey = WalletTestSupport.bootstrapHolderWithDeviceKey(
            deviceBindingService,
            walletUnitRepository,
            hsmService,
            holderId,
        )

        var ctx = orchestrator.resolveOffer(
            offerUri = """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
            holderId = holderId,
        )
        ctx = orchestrator.prepareAuthorization(ctx.sessionMeta.sessionId)
        val popJwt = WiaPopTestSupport.signPop(
            privateKey = deviceKey.privateKey,
            walletInstanceId = holderId,
            cnfJkt = ctx.wia!!.attestation!!.cnfJkt,
            audience = "https://issuer.example",
        )
        ctx = orchestrator.prepareAuthorization(ctx.sessionMeta.sessionId, popJwt)
        ctx = orchestrator.completeAuthorizationCode(ctx.sessionMeta.sessionId, "auth-code", ctx.preparedAuthorization!!.state)
        ctx = orchestrator.requestCredential(ctx.sessionMeta.sessionId, IssuanceRequest(credentialConfigurationId = "pid_jwt"))
        ctx = IssuanceConsentTestSupport.approveStorageIfPending(orchestrator, ctx, holderId)

        assertEquals(IssuanceState.CREDENTIAL_ISSUED, ctx.state)
        assertEquals(KaState.VALIDATED, ctx.ka?.state)
        assertNotNull(ctx.ka?.attestation)
        val events = eventStore.getEvents(ctx.sessionMeta.sessionId).map { it.type }
        assertTrue(events.contains("ka.generated"))
        assertTrue(events.contains("ka.attached"))
        assertTrue(events.contains("ka.validated"))
    }
}
