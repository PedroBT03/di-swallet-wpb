package di.swallet.wpb.security

import di.swallet.wpb.config.WscaSciProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class WscaSciGrantService(
    private val properties: WscaSciProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val grants = ConcurrentHashMap<String, Instant>()

    fun grant(holderId: String, ttlSeconds: Long = properties.consentGrantTtlSeconds) {
        if (holderId.isBlank()) return
        grants[holderId] = clock.instant().plusSeconds(ttlSeconds)
    }

    fun grantForRequest(holderId: String) {
        grant(holderId, ttlSeconds = 120)
    }

    fun isGranted(holderId: String): Boolean {
        val expiresAt = grants[holderId] ?: return false
        if (expiresAt.isBefore(clock.instant())) {
            grants.remove(holderId, expiresAt)
            return false
        }
        return true
    }

    fun revoke(holderId: String) {
        grants.remove(holderId)
    }
}
