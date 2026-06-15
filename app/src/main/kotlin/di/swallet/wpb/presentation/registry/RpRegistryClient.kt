/**
 * HTTP client port for TS5 RP registry read endpoints.
 */

package di.swallet.wpb.presentation.registry

/**
 * Fetches signed RP registry responses from TS5 HTTP endpoints.
 */
interface RpRegistryClient {
    /** Loads an RP record with GET /wrp/{identifier}. */
    fun getByIdentifier(identifier: String): RegistryHttpResponse?

    /** Searches for an RP record with GET /wrp?identifier=.... */
    fun queryByIdentifier(identifier: String): RegistryHttpResponse?

    /** Checks whether requested credentials fit the RP registered intended use. */
    fun checkIntendedUse(
        rpIdentifier: String,
        intendedUseIdentifier: String? = null,
        credentialFormat: String? = null,
        claimPath: String? = null,
        credentialMeta: String? = null,
        policyUrl: String? = null,
    ): RegistryHttpResponse?
}

/** Raw HTTP response from an RP registry endpoint. */
data class RegistryHttpResponse(
    val endpoint: String,
    val statusCode: Int,
    val body: String?,
    val contentType: String?,
    val jkuUrl: String? = null,
)
