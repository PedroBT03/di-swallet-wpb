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
import java.time.LocalDateTime

/**
 * Canonical device-to-wallet binding.
 *
 * This aggregate is intentionally separated from credential holder-key binding.
 */
@Entity
@Table(name = "device_wallet_bindings")
class DeviceWalletBinding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_unit_id", nullable = false)
    val walletUnit: WalletUnit,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val bindingType: DeviceBindingType = DeviceBindingType.DPOP,

    @Column(nullable = false, unique = true)
    val deviceKeyThumbprint: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: DeviceWalletBindingState = DeviceWalletBindingState.ACTIVE,

    @Column(nullable = false)
    val boundAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = true)
    val revokedAt: LocalDateTime? = null,
)

enum class DeviceWalletBindingState {
    ACTIVE,
    REVOKED,
}

enum class DeviceBindingType {
    DPOP,
    PID_KEY,
}
