/**
 * Health snapshot for the cached verifier trust material.
 */

package di.swallet.wpb.presentation.trust

import java.time.Instant

/** Health status reported for the trust snapshot cache. */
enum class TrustSnapshotHealthStatus {
    UP,
    DEGRADED,
    DOWN,
}

/** Current trust snapshot availability used by actuator health checks. */
data class TrustSnapshotHealth(
    val status: TrustSnapshotHealthStatus,
    val loadedAt: Instant?,
    val ageSeconds: Long?,
    val consecutiveFailures: Int,
    val reason: String?,
)
