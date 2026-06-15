/**
 * Servlet request attribute names used by wallet security components.
 */

package di.swallet.wpb.security

/**
 * Request attribute keys for authenticated holder context and holder log encryption key.
 */
object WalletSecurityAttributes {
    const val AUTHENTICATED_HOLDER_ID = "di.swallet.wpb.authenticatedHolderId"
    const val HOLDER_LOG_KEY = "di.swallet.wpb.holderLogKey"
}
