package di.swallet.wpb.transactionlog.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "transaction_log_entries")
class TransactionLogEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "holder_id", nullable = false)
    val holderId: String = "",

    @Column(name = "transaction_id", nullable = false, unique = true)
    val transactionId: String = "",

    @Column(name = "transaction_type", nullable = false)
    val transactionType: String = "",

    @Column(name = "transaction_result", nullable = false)
    val transactionResult: String = "",

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.now(),

    @Column(name = "ts10_schema_version", nullable = false)
    val ts10SchemaVersion: String = "1.2",

    @Column(name = "payload_ciphertext", nullable = false, columnDefinition = "TEXT")
    val payloadCiphertext: String = "",

    @Column(name = "integrity_mac", nullable = false)
    val integrityMac: String = "",

    @Column(name = "dek_mode", nullable = false)
    val dekMode: String = "SERVER",

    @Column(name = "deleted_by_user", nullable = false)
    var deletedByUser: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
