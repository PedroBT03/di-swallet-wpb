/**
 * Extracts holder ids embedded in wallet API request paths for authorization checks.
 */

package di.swallet.wpb.security

/**
 * Parses wallet REST paths to find holder-scoped resource segments.
 */
object WalletPathHolderExtractor {

    /**
     * Returns the holder id segment from known wallet API paths, if present.
     */
    fun extractHolderId(requestUri: String, method: String): String? {
        pathSegmentAfter(requestUri, "/api/v1/wallet/keys/")?.let { return it }
        pathSegmentAfter(requestUri, "/api/v1/wallet/sign/")?.let { return it }
        pathSegmentAfter(requestUri, "/api/v1/wallet/credentials/issue-sd/")?.let { return it }
        pathSegmentAfter(requestUri, "/api/v1/wallet/credentials/issue/")?.let { return it }

        if (method == "GET") {
            pathSegmentAfter(requestUri, "/api/v1/wallet/credentials/", endOfPath = true)?.let { segment ->
                if (!segment.all { it.isDigit() }) return segment
            }
        }
        return null
    }

    /**
     * Returns the first path segment immediately after the given prefix.
     */
    private fun pathSegmentAfter(uri: String, prefix: String, endOfPath: Boolean = false): String? {
        if (!uri.contains(prefix)) return null
        val remainder = uri.substringAfter(prefix)
        val segment = remainder.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
        if (endOfPath && remainder.contains('/')) return null
        return segment
    }
}
