/**
 * Spring Data repository for FIDO2 assertion challenge storage and cleanup.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

/**
 * Stores, retrieves, and deletes in-flight FIDO2 assertion challenges.
 */
interface Fido2AssertionChallengeRepository : JpaRepository<Fido2AssertionChallenge, Long> {
    /** Finds a stored assertion challenge for a user and challenge key. */
    fun findByUserIdAndChallengeKey(userId: String, challengeKey: String): Optional<Fido2AssertionChallenge>

    /** Removes a consumed or abandoned assertion challenge for a user. */
    fun deleteByUserIdAndChallengeKey(userId: String, challengeKey: String)

    /** Deletes all assertion challenges that have passed their expiration time. */
    @Modifying
    @Query("DELETE FROM Fido2AssertionChallenge c WHERE c.expiresAtEpochMillis < :nowEpochMillis")
    fun deleteExpired(@Param("nowEpochMillis") nowEpochMillis: Long): Int
}
