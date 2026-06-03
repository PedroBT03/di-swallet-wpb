package di.swallet.wpb.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Persisted Key Attestation (KA) as a first-class normative artifact.
 *
 * TS3 constraints such as one-time KA use and status maintenance apply at this
 * aggregate level, not only at key level.
 */
@Entity
@Table(name = "key_attestations")
class KeyAttestationRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_unit_id", nullable = false)
    val walletUnit: WalletUnit,

    @Column(nullable = false, unique = true)
    val attestationId: String = UUID.randomUUID().toString(),

    @Column(columnDefinition = "TEXT", nullable = false)
    val jwt: String,

    @Column(nullable = false)
    val keyStorage: String,

    @Column(nullable = false)
    val statusListUri: String,

    @Column(nullable = false)
    val statusListIndex: Int,

    @Column(nullable = false)
    val technicalExpiresAt: Instant,

    @Column(nullable = false)
    val statusMaintenanceExpiresAt: Instant,

    @Column(nullable = false)
    val issuedAt: Instant = Instant.now(),

    @Column(nullable = true)
    val consumedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: KeyAttestationState = KeyAttestationState.AVAILABLE,
)

enum class KeyAttestationState {
    AVAILABLE,
    CONSUMED,
    EXPIRED,
    REVOKED,
}
