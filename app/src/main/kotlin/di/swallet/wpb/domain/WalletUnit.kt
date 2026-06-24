/**
 * JPA entity and lifecycle states for the canonical wallet unit aggregate.
 */

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
 * Root wallet aggregate tracking holder identity, lifecycle state, and binding anchors.
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

/**
 * Allowed lifecycle states for a wallet unit (ARF / ARTE UC1 model).
 *
 * CANDIDATE  - provisioned by `/wallet/init`: device key bound, holder HSM key generated and WIA
 *              issued, but no citizen identity yet (anonymous, awaiting CMD identity in UC2).
 * VALID      - activated after identity establishment / first credential issuance (CMD).
 * SUSPENDED  - temporarily blocked; can return to VALID.
 * REVOKED    - permanently invalidated (security breach / holder request).
 * DELETED    - GDPR erasure terminal state.
 */
enum class WalletUnitState {
    CANDIDATE,
    VALID,
    SUSPENDED,
    REVOKED,
    DELETED,
}
