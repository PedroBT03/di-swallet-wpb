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
 * Key entry attested by a specific KA.
 *
 * One KA may contain multiple attested keys (batch issuance support).
 */
@Entity
@Table(name = "attested_keys")
class AttestedKeyRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "key_attestation_id", nullable = false)
    val keyAttestation: KeyAttestationRecord,

    @Column(nullable = false, unique = true)
    val keyId: String = UUID.randomUUID().toString(),

    @Column(nullable = false, unique = true)
    val keyAlias: String,

    @Column(nullable = false, unique = true)
    val keyThumbprint: String,

    @Column(nullable = false)
    val batchPosition: Int = 0,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: AttestedKeyState = AttestedKeyState.ATTESTED,
)

enum class AttestedKeyState {
    ATTESTED,
    BOUND,
    RETIRED,
    REVOKED,
}
