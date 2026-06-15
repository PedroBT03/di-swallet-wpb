package di.swallet.wpb

import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.ops.WeakSecretDefaults

/** Transaction log properties with documented test keys (unit tests only). */
fun testTransactionLogProperties(
    configure: TransactionLogProperties.() -> Unit = {},
): TransactionLogProperties = TransactionLogProperties().apply {
    encryptionKey = WeakSecretDefaults.KNOWN_WEAK_TX_ENC_KEY
    integrityKey = WeakSecretDefaults.KNOWN_WEAK_TX_INT_KEY
}.also(configure)
