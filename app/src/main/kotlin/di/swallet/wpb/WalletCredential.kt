package di.swallet.wpb

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Entity representing a Verifiable Credential stored in the Wallet.
 * Associates a specific set of identity data with an HSM-protected key.
 */
@Entity
@Table(name = "wallet_credentials")
class WalletCredential(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val userId: String,

    @Column(nullable = false)
    val credentialType: String, // e.g., "PID" (Person Identification Data)

    @Column(columnDefinition = "TEXT", nullable = false)
    val encryptedData: String, // The actual JWT/SD-JWT string

    @ManyToOne
    @JoinColumn(name = "wallet_key_id")
    val walletKey: WalletKey,

    val issuedAt: LocalDateTime = LocalDateTime.now()
)