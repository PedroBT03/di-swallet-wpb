package di.swallet.wpb.ops.health

import di.swallet.wpb.service.HsmService
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class HsmHealthIndicator(
    private val hsmService: HsmService,
) : HealthIndicator {
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
