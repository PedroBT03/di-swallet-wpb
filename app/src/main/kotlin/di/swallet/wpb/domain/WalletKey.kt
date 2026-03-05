package di.swallet.wpb.domain

import jakarta.persistence.*
import java.time.LocalDateTime

enum class KeyStatus {
    ACTIVE, SUSPENDED, REVOKED
}

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

    @Enumerated(EnumType.STRING)
    var status: KeyStatus = KeyStatus.ACTIVE,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)