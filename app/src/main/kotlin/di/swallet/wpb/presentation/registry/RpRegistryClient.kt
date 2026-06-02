package di.swallet.wpb.presentation.registry

interface RpRegistryClient {
    fun getByIdentifier(identifier: String): RegistryHttpResponse?

    fun queryByIdentifier(identifier: String): RegistryHttpResponse?

    fun checkIntendedUse(
        rpIdentifier: String,
        intendedUseIdentifier: String? = null,
        credentialFormat: String? = null,
        claimPath: String? = null,
        credentialMeta: String? = null,
        policyUrl: String? = null,
    ): RegistryHttpResponse?
}

data class RegistryHttpResponse(
    val endpoint: String,
    val statusCode: Int,
    val body: String?,
    val contentType: String?,
    val jkuUrl: String? = null,
)
