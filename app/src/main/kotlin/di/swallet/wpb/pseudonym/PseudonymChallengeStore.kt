package di.swallet.wpb.pseudonym

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class PseudonymChallengeStore {
    private data class Entry(val challenge: String, val expiresAt: Instant)

    private val challenges = ConcurrentHashMap<String, Entry>()

    fun store(pseudonymId: UUID, challenge: String, ttlSeconds: Long) {
        challenges[pseudonymId.toString()] = Entry(challenge, Instant.now().plusSeconds(ttlSeconds))
    }

    fun consume(pseudonymId: UUID, challenge: String): Boolean {
        val key = pseudonymId.toString()
        val entry = challenges.remove(key) ?: return false
        if (Instant.now().isAfter(entry.expiresAt)) return false
        return entry.challenge == challenge
    }
}
