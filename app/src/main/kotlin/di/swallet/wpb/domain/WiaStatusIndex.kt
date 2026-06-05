package di.swallet.wpb.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * Durable mapping from holder (+ optional issuer scope) to a WIA status-list bit index.
 */
@Entity
@Table(
    name = "wia_status_indexes",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_wia_status_holder_scope", columnNames = ["holder_id", "issuer_scope"]),
    ],
)
class WiaStatusIndex(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "holder_id", nullable = false)
    val holderId: String = "",

    /** Issuer identifier or "*" when status is not scoped per issuer. */
    @Column(name = "issuer_scope", nullable = false)
    val issuerScope: String = "*",

    @Column(name = "list_id", nullable = false)
    val listId: String = "",

    @Column(name = "status_index", nullable = false)
    val statusIndex: Int = 0,
)
