package di.swallet.wpb.security

object WalletPathHolderExtractor {

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

    private fun pathSegmentAfter(uri: String, prefix: String, endOfPath: Boolean = false): String? {
        if (!uri.contains(prefix)) return null
        val remainder = uri.substringAfter(prefix)
        val segment = remainder.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
        if (endOfPath && remainder.contains('/')) return null
        return segment
    }
}
