/**
 * Short-lived holder session tokens for WIAM_15 dashboard access after WebAuthn login.
 */

package di.swallet.wpb.security

import di.swallet.wpb.config.WalletProperties
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Active holder session metadata. */
data class HolderSession(
    val holderId: String,
    val expiresAt: Instant,
)

/** Response returned after a successful WebAuthn login session exchange. */
data class HolderSessionToken(
    val sessionToken: String,
    val holderId: String,
    val expiresAt: Instant,
)

/**
 * In-memory holder sessions for lab deployments (single-node WPB).
 */
@Service
class HolderSessionService(
    private val walletProperties: WalletProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sessions = ConcurrentHashMap<String, HolderSession>()
    private val secureRandom = SecureRandom()

    /**
     * Issues a new session token for the authenticated holder.
     */
    fun create(holderId: String): HolderSessionToken {
        val ttlSeconds = walletProperties.session.ttlSeconds
        val expiresAt = clock.instant().plusSeconds(ttlSeconds)
        val token = generateToken()
        sessions[token] = HolderSession(holderId = holderId, expiresAt = expiresAt)
        return HolderSessionToken(sessionToken = token, holderId = holderId, expiresAt = expiresAt)
    }

    /**
     * Resolves a session token to the holder id when still valid.
     */
    fun resolve(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val session = sessions[token] ?: return null
        if (session.expiresAt.isBefore(clock.instant())) {
            sessions.remove(token)
            return null
        }
        return session.holderId
    }

    /**
     * Revokes a holder session token.
     */
    fun revoke(token: String?) {
        if (!token.isNullOrBlank()) {
            sessions.remove(token)
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
