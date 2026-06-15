/**
 * Configuration properties for encrypted holder transaction logs.
 */

package di.swallet.wpb.config

import di.swallet.wpb.transactionlog.crypto.TransactionLogDekMode
import org.springframework.boot.context.properties.ConfigurationProperties

/** Binds `wpb.transaction-log.*` settings for TS10 transaction log encryption and retention. */
@ConfigurationProperties(prefix = "wpb.transaction-log")
class TransactionLogProperties {
    /**
     * Payload encryption model:
     * - server: per-holder DEK derived from WPB master key (WPB can decrypt).
     * - holder: per-holder DEK supplied by WPI via X-Wallet-Log-Key (WPB cannot decrypt without it).
     */
    var dekMode: String = "server"

    /** Base64-encoded 32-byte AES key for per-holder payload encryption at rest. */
    var encryptionKey: String = ""

    /** Base64-encoded 32-byte HMAC key for entry integrity. */
    var integrityKey: String = ""

    /**
     * Parses [dekMode] into the typed transaction log DEK mode enum.
     */
    fun resolvedDekMode(): TransactionLogDekMode = TransactionLogDekMode.fromConfig(dekMode)

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
