package di.swallet.wpb

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

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
    val userId: String,

    @Column(nullable = false, unique = true)
    val keyAlias: String, // The alias used inside the HSM KeyStore

    @Column(columnDefinition = "TEXT")
    val publicKeyBase64: String,

    @Enumerated(EnumType.STRING)
    var status: KeyStatus = KeyStatus.ACTIVE,

    val createdAt: LocalDateTime = LocalDateTime.now()
)