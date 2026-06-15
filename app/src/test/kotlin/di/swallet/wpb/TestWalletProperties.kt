/**
 * Factory for wallet properties preconfigured with documented weak test keys.
 */

package di.swallet.wpb

import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.ops.WeakSecretDefaults

/** Wallet properties with the documented test disclosure key (unit tests only). */
fun testWalletProperties(
    configure: WalletProperties.() -> Unit = {},
): WalletProperties = WalletProperties(
    disclosures = WalletProperties.DisclosuresProperties(
        encryptionKey = WeakSecretDefaults.KNOWN_WEAK_DISCLOSURE_KEY,
    ),
).also(configure)
