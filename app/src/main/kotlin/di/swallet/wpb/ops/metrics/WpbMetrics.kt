package di.swallet.wpb.ops.metrics

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationState
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class WpbMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun recordPresentationTerminal(context: PresentationContext) {
        val outcome = when (context.state) {
            PresentationState.DISPATCHED -> if (context.error == null) "dispatched_positive" else "dispatched_negative"
            PresentationState.REJECTED -> context.error?.code ?: "rejected"
            PresentationState.FAILED -> context.error?.code ?: "failed"
            PresentationState.EXPIRED -> "expired"
            else -> return
        }
        meterRegistry.counter("wpb.presentation.sessions", "outcome", outcome).increment()
    }

    fun recordIssuanceTerminal(context: IssuanceContext) {
        val outcome = when (context.state) {
            IssuanceState.CREDENTIAL_ISSUED, IssuanceState.DEFERRED_ISSUED -> "issued"
            IssuanceState.REJECTED -> context.error?.code ?: "rejected"
            IssuanceState.FAILED -> context.error?.code ?: "failed"
            IssuanceState.EXPIRED -> "expired"
            else -> return
        }
        meterRegistry.counter("wpb.issuance.sessions", "outcome", outcome).increment()
    }

    fun recordTrustValidation(result: String) {
        meterRegistry.counter("wpb.trust.validation", "result", result).increment()
    }

    fun recordFido2Failure(reason: String) {
        meterRegistry.counter("wpb.security.fido2.failures", "reason", reason).increment()
    }

    fun <T> timeStatusList(block: () -> T): T =
        statusListTimer.record(block)!!

    fun <T> timeRegistryLookup(block: () -> T): T =
        registryLookupTimer.record(block)!!

    private val statusListTimer: Timer by lazy {
        Timer.builder("wpb.statuslist.get")
            .description("Status list publication GET latency")
            .register(meterRegistry)
    }

    private val registryLookupTimer: Timer by lazy {
        Timer.builder("wpb.registry.lookup")
            .description("TS5 registry HTTP lookup latency")
            .register(meterRegistry)
    }
}
