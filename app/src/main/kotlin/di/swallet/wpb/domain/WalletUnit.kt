package di.swallet.wpb.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * Phase 8 canonical aggregate for wallet-level lifecycle.
 *
 * This is distinct from key and credential aggregates and anchors:
 * - device-to-wallet bindings
 * - attestation provenance (WIA / KA)
 * - wallet lifecycle transitions
 */
@Entity
@Table(name = "wallet_units")
class WalletUnit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val walletId: String = UUID.randomUUID().toString(),

    @Column(nullable = true)
    val holderId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: WalletUnitState = WalletUnitState.CANDIDATE,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class WalletUnitState {
    CANDIDATE,
    OPERATIONAL,
    VALID,
    SUSPENDED,
    REVOKED,
    DELETED,
}
