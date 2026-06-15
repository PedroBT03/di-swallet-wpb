package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface Fido2AssertionChallengeRepository : JpaRepository<Fido2AssertionChallenge, Long> {
    fun findByUserIdAndChallengeKey(userId: String, challengeKey: String): Optional<Fido2AssertionChallenge>

    fun deleteByUserIdAndChallengeKey(userId: String, challengeKey: String)

    @Modifying
    @Query("DELETE FROM Fido2AssertionChallenge c WHERE c.expiresAtEpochMillis < :nowEpochMillis")
    fun deleteExpired(@Param("nowEpochMillis") nowEpochMillis: Long): Int
}
