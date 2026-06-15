/**
 * Detects known weak crypto secret defaults that must not be used in production.
 */

package di.swallet.wpb.ops

import di.swallet.wpb.transactionlog.crypto.TransactionLogDekMode

/**
 * Checks disclosure and transaction-log keys against known weak development defaults.
 */
object WeakCryptoSecretPolicy {
    /**
     * Returns property keys that still use blank or known weak secret values.
     */
    fun violations(
        disclosureEncryptionKey: String,
        transactionLogEncryptionKey: String,
        transactionLogIntegrityKey: String,
        dekMode: TransactionLogDekMode,
    ): List<String> {
        val out = mutableListOf<String>()
        if (isWeakDisclosureKey(disclosureEncryptionKey)) {
            out += "wallet.disclosures.encryption-key"
        }
        if (isWeakIntegrityKey(transactionLogIntegrityKey)) {
            out += "wpb.transaction-log.integrity-key"
        }
        if (dekMode == TransactionLogDekMode.SERVER && isWeakTxEncryptionKey(transactionLogEncryptionKey)) {
            out += "wpb.transaction-log.encryption-key"
        }
        return out
    }

    /**
     * Returns true when the disclosure encryption key is blank or a known dev default.
     */
    private fun isWeakDisclosureKey(key: String): Boolean =
        key.isBlank() || key == WeakSecretDefaults.KNOWN_WEAK_DISCLOSURE_KEY

    /**
     * Returns true when the transaction log integrity key is blank or a known dev default.
     */
    private fun isWeakIntegrityKey(key: String): Boolean =
        key.isBlank() || key == WeakSecretDefaults.KNOWN_WEAK_TX_INT_KEY

    /**
     * Returns true when the transaction log encryption key is blank or a known dev default.
     */
    private fun isWeakTxEncryptionKey(key: String): Boolean =
        key.isBlank() || key == WeakSecretDefaults.KNOWN_WEAK_TX_ENC_KEY
}
