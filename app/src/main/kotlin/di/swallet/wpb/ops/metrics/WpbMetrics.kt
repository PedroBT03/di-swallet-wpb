/**
 * Micrometer counters and timers for WPB operational metrics.
 */

package di.swallet.wpb.ops.metrics

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationState
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Records presentation, issuance, trust, FIDO2, and latency metrics for observability.
 */
@Component
class WpbMetrics(
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Increments a counter when a presentation session reaches a terminal state.
     */
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

    /**
     * Increments a counter when an issuance session reaches a terminal state.
     */
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

    /**
     * Increments a counter for verifier trust validation outcomes.
     */
    fun recordTrustValidation(result: String) {
        meterRegistry.counter("wpb.trust.validation", "result", result).increment()
    }

    /**
     * Increments a counter when FIDO2 authorization fails with the given reason tag.
     */
    fun recordFido2Failure(reason: String) {
        meterRegistry.counter("wpb.security.fido2.failures", "reason", reason).increment()
    }

    /**
     * Measures latency while executing a status list publication request.
     */
    fun <T> timeStatusList(block: () -> T): T =
        statusListTimer.record(block)!!

    /**
     * Measures latency while executing an RP registry lookup.
     */
    fun <T> timeRegistryLookup(block: () -> T): T =
        registryLookupTimer.record(block)!!

    private val statusListTimer: Timer by lazy {
        Timer.builder("wpb.statuslist.get")
            .description("Status list publication GET latency")
            .register(meterRegistry)
    }

    private val registryLookupTimer: Timer by lazy {
        Timer.builder("wpb.registry.lookup")
            .description("RP registry HTTP lookup latency")
            .register(meterRegistry)
    }
}
