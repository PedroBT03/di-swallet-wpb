package di.swallet.wpb.ops.health

import di.swallet.wpb.presentation.trust.TrustSnapshotHealth
import di.swallet.wpb.presentation.trust.TrustSnapshotHealthStatus
import di.swallet.wpb.presentation.trust.TrustSnapshotService
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class TrustSnapshotHealthIndicator(
    private val trustSnapshotService: TrustSnapshotService,
) : HealthIndicator {
    override fun health(): Health {
        val snapshotHealth = trustSnapshotService.health()
        val builder = when (snapshotHealth.status) {
            TrustSnapshotHealthStatus.UP -> Health.up()
            TrustSnapshotHealthStatus.DEGRADED -> Health.status("DEGRADED")
            TrustSnapshotHealthStatus.DOWN -> Health.down()
        }
        snapshotHealth.loadedAt?.let { builder.withDetail("loadedAt", it.toString()) }
        snapshotHealth.ageSeconds?.let { builder.withDetail("ageSeconds", it) }
        builder.withDetail("consecutiveRefreshFailures", snapshotHealth.consecutiveFailures)
        snapshotHealth.reason?.let { builder.withDetail("reason", it) }
        return builder.build()
    }
}
