/**
 * In-memory store for one-time WebAuthn challenges tied to pseudonym credential IDs.
 */

package di.swallet.wpb.pseudonym

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds short-lived WebAuthn challenges per pseudonym and validates them on single use.
 */
@Component
class PseudonymChallengeStore {
    /** Stored challenge value with its expiration timestamp. */
    private data class Entry(val challenge: String, val expiresAt: Instant)

    private val challenges = ConcurrentHashMap<String, Entry>()

    /**
     * Saves a challenge for the given pseudonym with the configured time-to-live.
     */
    fun store(pseudonymId: UUID, challenge: String, ttlSeconds: Long) {
        challenges[pseudonymId.toString()] = Entry(challenge, Instant.now().plusSeconds(ttlSeconds))
    }

    /**
     * Removes and validates a challenge, returning false if missing, expired, or mismatched.
     */
    fun consume(pseudonymId: UUID, challenge: String): Boolean {
        val key = pseudonymId.toString()
        val entry = challenges.remove(key) ?: return false
        if (Instant.now().isAfter(entry.expiresAt)) return false
        return entry.challenge == challenge
    }
}
