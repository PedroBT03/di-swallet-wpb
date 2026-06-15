/**
 * Database-backed store for FIDO2 WebAuthn assertion challenges.
 */

package di.swallet.wpb.security

import com.yubico.webauthn.AssertionRequest
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.domain.Fido2AssertionChallenge
import di.swallet.wpb.domain.Fido2AssertionChallengeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Durable WebAuthn assertion challenge store (cluster-safe via shared database).
 * Multiple concurrent challenges per holder are keyed by challenge value.
 */
@Service
class ChallengeService(
    private val walletProperties: WalletProperties,
    private val challengeRepository: Fido2AssertionChallengeRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val ttlSeconds get() = walletProperties.challenge.ttlSeconds

    /**
     * Persists a new assertion request keyed by its challenge value and expiry time.
     */
    @Transactional
    fun storeRequest(userId: String, request: AssertionRequest) {
        val challengeKey = request.publicKeyCredentialRequestOptions.challenge.base64Url
        val expiresAtEpochMillis = System.currentTimeMillis() + ttlSeconds * 1000L
        challengeRepository.save(
            Fido2AssertionChallenge(
                userId = userId,
                challengeKey = challengeKey,
                requestJson = AssertionRequestCodec.encode(request),
                expiresAtEpochMillis = expiresAtEpochMillis,
            ),
        )
        logger.debug("SecurityPolicy: Stored FIDO2 challenge for user {} (key={})", userId, challengeKey)
    }

    /**
     * Loads a stored assertion request or removes and returns null when it has expired.
     */
    @Transactional(readOnly = true)
    fun getRequest(userId: String, challengeKey: String): AssertionRequest? {
        val stored = challengeRepository.findByUserIdAndChallengeKey(userId, challengeKey).orElse(null)
            ?: return null
        if (System.currentTimeMillis() > stored.expiresAtEpochMillis) {
            challengeRepository.deleteByUserIdAndChallengeKey(userId, challengeKey)
            logger.warn("SecurityPolicy: Challenge expired for user {}", userId)
            return null
        }
        return AssertionRequestCodec.decode(stored.requestJson)
    }

    /**
     * Deletes a consumed or abandoned challenge from the store.
     */
    @Transactional
    fun removeRequest(userId: String, challengeKey: String) {
        challengeRepository.deleteByUserIdAndChallengeKey(userId, challengeKey)
    }

    /**
     * Returns the raw base64url challenge string for a stored request, if still valid.
     */
    fun getRawChallenge(userId: String, challengeKey: String): String? =
        getRequest(userId, challengeKey)?.publicKeyCredentialRequestOptions?.challenge?.base64Url

    @Scheduled(
        fixedDelayString = "\${wallet.challenge.ttl-seconds:120}000",
        initialDelayString = "\${wallet.challenge.ttl-seconds:120}000",
    )
    @Transactional
    /**
     * Removes expired challenges from the database on a fixed schedule.
     */
    fun purgeExpired() {
        val removed = challengeRepository.deleteExpired(System.currentTimeMillis())
        if (removed > 0) {
            logger.info("SecurityPolicy: Purged {} expired FIDO2 challenge(s)", removed)
        }
    }
}
