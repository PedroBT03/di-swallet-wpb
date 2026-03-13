package di.swallet.wpb.security

import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Stateful service to manage cryptographic challenges (nonces).
 */
@Service
class ChallengeService {
    private val secureRandom = SecureRandom()
    private val challengeStore = ConcurrentHashMap<String, String>()

    /**
     * Generates a new random challenge for a user.
     */
    fun generateChallenge(userId: String): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        challengeStore[userId] = challenge
        return challenge
    }

    /**
     * Retrieves the current challenge for a user without removing it.
     */
    fun getChallengeForUser(userId: String): String? {
        return challengeStore[userId]
    }

    /**
     * Validates and consumes a challenge (removes it from memory).
     */
    fun validateChallenge(userId: String, receivedChallenge: String): Boolean {
        val storedChallenge = challengeStore.remove(userId)
        return storedChallenge != null && storedChallenge == receivedChallenge
    }
}