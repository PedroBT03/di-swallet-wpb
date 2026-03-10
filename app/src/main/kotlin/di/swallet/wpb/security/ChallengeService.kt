package di.swallet.wpb.security

import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Service to manage cryptographic challenges (nonces).
 * Ensures each authorization attempt is unique and time-bound.
 */
@Service
class ChallengeService {
    private val secureRandom = SecureRandom()
    // In-memory store for challenges (UserId -> Challenge)
    private val challengeStore = ConcurrentHashMap<String, String>()

    /**
     * Generates a new random challenge for a user.
     */
    fun generateChallenge(userId: String): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val challenge = Base64.getEncoder().encodeToString(bytes)
        challengeStore[userId] = challenge
        return challenge
    }

    /**
     * Validates and consumes a challenge.
     */
    fun validateChallenge(userId: String, receivedChallenge: String): Boolean {
        val storedChallenge = challengeStore.remove(userId)
        return storedChallenge != null && storedChallenge == receivedChallenge
    }
}