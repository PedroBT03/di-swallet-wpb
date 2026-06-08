package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wpb.transaction-log")
class TransactionLogProperties {
    /** Base64-encoded 32-byte AES key for per-holder payload encryption at rest. */
    var encryptionKey: String = ""

    /** Base64-encoded 32-byte HMAC key for entry integrity. */
    var integrityKey: String = ""

    /** TS10 schema version written into new entries. */
    var ts10SchemaVersion: String = "1.2"

    /** Minimum retention in days before pruning (DASH_02a). */
    var retentionDays: Long = 365

    /** Maximum entries per holder before retention pruning. */
    var maxEntriesPerHolder: Int = 10_000

    /** Grace period in days after retention warning before physical prune. */
    var retentionGraceDays: Long = 30

    /** Cron for retention job. */
    var retentionCron: String = "0 30 2 * * *"
}
