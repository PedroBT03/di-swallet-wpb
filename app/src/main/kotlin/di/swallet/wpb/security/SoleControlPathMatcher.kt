/**
 * Classifies HTTP routes that require a fresh FIDO2 assertion (WIAM_14 / RPA_08 / ISSU_11).
 */

package di.swallet.wpb.security

/**
 * Routes that must use sole-control FIDO2 rather than a holder session token.
 */
object SoleControlPathMatcher {

    /**
     * Returns true when the request must present a fresh WebAuthn assertion.
     */
    fun requiresSoleControl(method: String, requestUri: String): Boolean {
        val uri = requestUri.substringBefore('?')
        if (method == "POST" && uri.endsWith("/consent")) {
            return true
        }
        if (method == "POST" && uri.contains("/credential/request")) {
            return true
        }
        if (method == "POST" && uri.contains("/credentials/issue")) {
            return true
        }
        if (method == "POST" && uri.contains("/credentials/") && uri.endsWith("/presentation")) {
            return true
        }
        if (method == "POST" && uri.endsWith("/revoke")) {
            return true
        }
        if (method == "DELETE") {
            return true
        }
        if (method == "POST" && Regex("""/keys/[^/]+$""").containsMatchIn(uri)) {
            return true
        }
        if (method == "POST" && uri.contains("/sign/")) {
            return true
        }
        if (
            method == "POST" &&
            uri.contains("/pseudonyms/") &&
            (uri.endsWith("/registration/finish") || uri.endsWith("/authentication/finish"))
        ) {
            return true
        }
        return false
    }
}
