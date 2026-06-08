package di.swallet.wpb.pseudonym

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "pseudonym_credentials")
class PseudonymCredential(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "holder_id", nullable = false)
    val holderId: String = "",

    @Column(name = "rp_id", nullable = false)
    val rpId: String = "",

    @Column(name = "credential_id")
    var credentialId: String? = null,

    @Column(name = "key_alias")
    var keyAlias: String? = null,

    @Column(name = "user_handle", nullable = false)
    val userHandle: String = "",

    @Column
    var alias: String? = null,

    @Column(name = "sign_count", nullable = false)
    var signCount: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PseudonymStatus = PseudonymStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
)

enum class PseudonymStatus {
    PENDING,
    REGISTERED,
}
