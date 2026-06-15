package di.swallet.wpb.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "fido2_assertion_challenges")
class Fido2AssertionChallenge(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false)
    val userId: String,
    @Column(nullable = false, unique = true)
    val challengeKey: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val requestJson: String,
    @Column(nullable = false)
    val expiresAtEpochMillis: Long,
    @Column(nullable = false)
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)
