/**
 * Short-lived SCI grants that authorize HSM access after holder consent or FIDO2 auth.
 */

package di.swallet.wpb.security

import di.swallet.wpb.config.WscaSciProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-holder SCI grants with configurable TTL for multi-step OID4 flows.
 */
@Component
class WscaSciGrantService(
    private val properties: WscaSciProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val grants = ConcurrentHashMap<String, Instant>()

    /**
     * Records a new SCI grant for the holder that expires after the given TTL.
     */
    fun grant(holderId: String, ttlSeconds: Long = properties.consentGrantTtlSeconds) {
        if (holderId.isBlank()) return
        grants[holderId] = clock.instant().plusSeconds(ttlSeconds)
    }

    /**
     * Grants SCI access for the current HTTP request with a fixed two-minute TTL.
     */
    fun grantForRequest(holderId: String) {
        grant(holderId, ttlSeconds = 120)
    }

    /**
     * Returns true when an unexpired SCI grant exists for the holder.
     */
    fun isGranted(holderId: String): Boolean {
        val expiresAt = grants[holderId] ?: return false
        if (expiresAt.isBefore(clock.instant())) {
            grants.remove(holderId, expiresAt)
            return false
        }
        return true
    }

    /**
     * Removes any active SCI grant for the holder.
     */
    fun revoke(holderId: String) {
        grants.remove(holderId)
    }
}
