package di.swallet.wpb.presentation.registry

import di.swallet.wpb.config.OpenId4VpProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * TS5 v1.2 client for RP registry read endpoints:
 * - GET /wrp/{identifier}
 * - GET /wrp?identifier=...
 * - GET /wrp/check-intended-use?... (rpidentifier mandatory)
 */
@Component
class Ts5RpRegistryHttpClient(
    private val properties: OpenId4VpProperties,
) : RpRegistryClient {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getByIdentifier(identifier: String): RegistryHttpResponse? {
        val base = properties.registry.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val endpoint = "$base/wrp/${encode(identifier)}"
        return fetch(endpoint)
    }

    override fun queryByIdentifier(identifier: String): RegistryHttpResponse? {
        val base = properties.registry.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val endpoint = "$base/wrp?identifier=${encode(identifier)}"
        return fetch(endpoint)
    }

    override fun checkIntendedUse(
        rpIdentifier: String,
        intendedUseIdentifier: String?,
        credentialFormat: String?,
        claimPath: String?,
        credentialMeta: String?,
        policyUrl: String?,
    ): RegistryHttpResponse? {
        val base = properties.registry.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val params = linkedMapOf<String, String>()
        params["rpidentifier"] = rpIdentifier
        intendedUseIdentifier?.takeIf { it.isNotBlank() }?.let { params["intendeduseidentifier"] = it }
        credentialFormat?.takeIf { it.isNotBlank() }?.let { params["credentialformat"] = it }
        claimPath?.takeIf { it.isNotBlank() }?.let { params["claimpath"] = it }
        credentialMeta?.takeIf { it.isNotBlank() }?.let { params["credentialmeta"] = it }
        policyUrl?.takeIf { it.isNotBlank() }?.let { params["policyurl"] = it }
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return fetch("$base/wrp/check-intended-use?$query")
    }

    private fun fetch(endpoint: String): RegistryHttpResponse {
        validateRemoteUrlPolicy(endpoint)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = properties.registry.connectTimeoutMs.toInt()
            readTimeout = properties.registry.readTimeoutMs.toInt()
            requestMethod = "GET"
            setRequestProperty("Accept", "application/jwt, application/json")
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }
        val contentType = conn.contentType
        val jku = conn.getHeaderField("x-jku-url")
        logger.debug("event=registry.http endpoint={} status={}", endpoint, status)
        return RegistryHttpResponse(
            endpoint = endpoint,
            statusCode = status,
            body = body,
            contentType = contentType,
            jkuUrl = jku,
        )
    }

    private fun validateRemoteUrlPolicy(rawUrl: String) {
        val uri = runCatching { URI(rawUrl) }.getOrElse {
            throw IllegalArgumentException("registry URL is invalid: ${it.message}")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        require(host.isNotBlank()) { "registry URL host is required" }
        if (!properties.demoMode && scheme != "https") {
            throw IllegalStateException("production mode requires HTTPS registry URL")
        }
        val allowlist = properties.registry.remoteAllowedHosts()
        if (!properties.demoMode) {
            require(allowlist.isNotEmpty()) { "production mode requires non-empty registry host allow-list" }
            if (host !in allowlist) {
                throw IllegalStateException("registry host '$host' is not allow-listed")
            }
        } else if (allowlist.isNotEmpty() && host !in allowlist) {
            throw IllegalStateException("demo mode host '$host' is not allow-listed by configured registry policy")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
