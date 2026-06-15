/**
 * DEK mode for transaction log payload encryption (server-derived or holder-provided key).
 */

package di.swallet.wpb.transactionlog.crypto

/** How transaction log payloads are encrypted at rest. */
enum class TransactionLogDekMode {
    SERVER,
    HOLDER,
    ;

    companion object {
        /** Parses configuration string; defaults to SERVER when unknown. */
        fun fromConfig(value: String): TransactionLogDekMode =
            when (value.trim().lowercase()) {
                "holder" -> HOLDER
                else -> SERVER
            }
    }
}
