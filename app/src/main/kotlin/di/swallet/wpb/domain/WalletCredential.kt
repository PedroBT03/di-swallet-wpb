package di.swallet.wpb.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Represents a Verifiable Credential (e.g., PID) stored in the wallet.
 * It links the identity data (SD-JWT) to the hardware key used to sign it.
 */
@Entity
@Table(name = "wallet_credentials")
class WalletCredential(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val userId: String = "",

    @Column(nullable = false)
    val credentialType: String = "", // e.g., "PID"

    @Column(columnDefinition = "TEXT", nullable = false)
    val encodedData: String = "", // Signed SD-JWT (without disclosures)

    @JsonIgnore
    @Column(columnDefinition = "TEXT", nullable = false)
    val encryptedDisclosures: String = "", // AES-GCM encrypted disclosure set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_key_id")
    val walletKey: WalletKey? = null,

    @Column(nullable = false)
    val issuedAt: LocalDateTime = LocalDateTime.now()
)