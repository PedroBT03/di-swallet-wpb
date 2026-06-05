package di.swallet.wpb.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * Durable mapping from holder (+ issuer scope + attestation fingerprint) to a KA status-list bit index.
 */
@Entity
@Table(
    name = "ka_status_indexes",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_ka_status_holder_scope_fingerprint",
            columnNames = ["holder_id", "issuer_scope", "attestation_fingerprint"],
        ),
    ],
)
class KaStatusIndex(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "holder_id", nullable = false)
    val holderId: String = "",

    /** Issuer identifier or "*" when status is not scoped per issuer. */
    @Column(name = "issuer_scope", nullable = false)
    val issuerScope: String = "*",

    @Column(name = "attestation_fingerprint", nullable = false)
    val attestationFingerprint: String = "",

    @Column(name = "list_id", nullable = false)
    val listId: String = "",

    @Column(name = "status_index", nullable = false)
    val statusIndex: Int = 0,
)
