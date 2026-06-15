/**
 * Actuator health indicator for PKCS#11 HSM connectivity.
 */

package di.swallet.wpb.ops.health

import di.swallet.wpb.service.HsmService
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Probes the HSM PKCS#11 session and reports token label and key entry count.
 */
@Component
class HsmHealthIndicator(
    private val hsmService: HsmService,
) : HealthIndicator {
    /**
     * Returns UP when a PKCS#11 session is reachable, otherwise DOWN with the probe reason.
     */
    override fun health(): Health {
        val probe = hsmService.probePkcs11Session()
        return if (probe.reachable) {
            Health.up()
                .withDetail("tokenLabel", probe.tokenLabel ?: "unknown")
                .withDetail("keyEntryCount", probe.keyEntryCount)
                .build()
        } else {
            Health.down()
                .withDetail("reason", probe.message ?: "PKCS#11 session unavailable")
                .build()
        }
    }
}
