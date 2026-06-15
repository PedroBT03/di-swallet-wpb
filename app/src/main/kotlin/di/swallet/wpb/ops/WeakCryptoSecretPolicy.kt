package di.swallet.wpb.ops

import di.swallet.wpb.transactionlog.crypto.TransactionLogDekMode

object WeakCryptoSecretPolicy {
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

    private fun isWeakDisclosureKey(key: String): Boolean =
        key.isBlank() || key == WeakSecretDefaults.KNOWN_WEAK_DISCLOSURE_KEY

    private fun isWeakIntegrityKey(key: String): Boolean =
        key.isBlank() || key == WeakSecretDefaults.KNOWN_WEAK_TX_INT_KEY

    private fun isWeakTxEncryptionKey(key: String): Boolean =
        key.isBlank() || key == WeakSecretDefaults.KNOWN_WEAK_TX_ENC_KEY
}
