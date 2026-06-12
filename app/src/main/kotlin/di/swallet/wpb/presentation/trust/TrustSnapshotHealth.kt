package di.swallet.wpb.presentation.trust

import java.time.Instant

enum class TrustSnapshotHealthStatus {
    UP,
    DEGRADED,
    DOWN,
}

data class TrustSnapshotHealth(
    val status: TrustSnapshotHealthStatus,
    val loadedAt: Instant?,
    val ageSeconds: Long?,
    val consecutiveFailures: Int,
    val reason: String?,
)
