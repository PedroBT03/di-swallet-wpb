/**
 * Parsed reference to an external or WP-managed credential status list entry.
 */

package di.swallet.wpb.revocation

/**
 * Holds a credential's status list URI, bit index, and whether revocation is wallet-provider managed.
 */
data class CredentialStatusReference(
    val listUri: String? = null,
    val listIndex: Int? = null,
    val managedByWalletProvider: Boolean = false,
)
