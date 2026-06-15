/**
 * JPA entity for the persisted global revocation and allocation bitstrings.
 */

package di.swallet.wpb.domain

import jakarta.persistence.*

/**
 * Database row holding the primary status list bitstrings, capacity, and legacy next-index counter.
 */
@Entity
@Table(name = "status_lists")
class StatusList(
    @Id
    val id: String = "PRIMARY_LIST",

    @Column(columnDefinition = "BYTEA", nullable = false)
    var bitstring: ByteArray = ByteArray(0),

    @Column(nullable = false)
    var nextIndex: Int = 0,

    @Column(nullable = false)
    var capacity: Int = 131_072,

    @Column(name = "allocated_bitstring", columnDefinition = "BYTEA", nullable = false)
    var allocatedBitstring: ByteArray = ByteArray(0),
)
