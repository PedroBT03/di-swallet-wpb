package di.swallet.wpb.security

import com.yubico.webauthn.AssertionRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import di.swallet.wpb.config.WalletProperties

/**
 * Stateful service to manage single-use WebAuthn Assertion Requests.
 * Challenges expire after a configurable TTL to prevent replay attacks.
 */
@Service
class ChallengeService(
    private val walletProperties: WalletProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private data class StoredRequest(
        val request: AssertionRequest,
        val expiresAt: Instant
    )

    private val requestStore = ConcurrentHashMap<String, StoredRequest>()

    private val ttlSeconds get() = walletProperties.challenge.ttlSeconds

    // Background cleanup of expired challenges
    init {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            { purgeExpired() },
            ttlSeconds, ttlSeconds, TimeUnit.SECONDS
        )
    }

    /**
     * Stores a new assertion request for the given user ID. If a challenge already exists, it will be replaced.
     */
    fun storeRequest(userId: String, request: AssertionRequest) {
        if (requestStore.containsKey(userId)) {
            logger.warn("SecurityPolicy: Challenge already active for user $userId — replacing")
        }

        requestStore[userId] = StoredRequest(
            request = request,
            expiresAt = Instant.now().plusSeconds(ttlSeconds)
        )
    }

    /**
     * Returns the request only if it exists and has not expired.
     */
    fun getRequest(userId: String): AssertionRequest? {
        val stored = requestStore[userId] ?: return null
        if (Instant.now().isAfter(stored.expiresAt)) {
            requestStore.remove(userId)
            logger.warn("SecurityPolicy: Challenge expired for user $userId")
            return null
        }
        return stored.request
    }

    fun removeRequest(userId: String) {
        requestStore.remove(userId)
    }

    fun getRawChallenge(userId: String): String? {
        return getRequest(userId)?.publicKeyCredentialRequestOptions?.challenge?.base64Url
    }

    private fun purgeExpired() {
        val now = Instant.now()
        val expired = requestStore.entries.filter { now.isAfter(it.value.expiresAt) }.map { it.key }
        expired.forEach { requestStore.remove(it) }
        if (expired.isNotEmpty()) logger.info("SecurityPolicy: Purged ${expired.size} expired challenge(s)")
    }
}