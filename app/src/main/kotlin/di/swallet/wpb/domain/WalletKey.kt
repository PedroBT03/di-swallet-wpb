package di.swallet.wpb.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Entity representing a hardware key. 
 * Status is managed via a bitstring index (0 = ACTIVE, 1 = REVOKED).
 */
@Entity
@Table(name = "wallet_keys")
class WalletKey(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val userId: String = "",

    @Column(nullable = false, unique = true)
    val keyAlias: String = "",

    @Column(columnDefinition = "TEXT")
    val publicKeyBase64: String = "",

    // The index of this key in the global revocation bitstring
    @Column(nullable = false)
    val revocationIndex: Int = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)