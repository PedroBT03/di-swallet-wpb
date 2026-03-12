package di.swallet.wpb.domain

import jakarta.persistence.*

/**
 * Entity representing the global Status List state.
 * It stores the raw binary data of the bitstring and the next available index.
 */
@Entity
@Table(name = "status_lists")
class StatusList(
    @Id
    val id: String = "PRIMARY_LIST",

    @Column(columnDefinition = "BYTEA", nullable = false)
    var bitstring: ByteArray = ByteArray(0),

    @Column(nullable = false)
    var nextIndex: Int = 0
)