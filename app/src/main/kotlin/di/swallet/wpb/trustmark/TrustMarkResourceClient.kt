/**
 * HTTP client with in-memory caching for remote TrustMarkResource JSON payloads.
 */

package di.swallet.wpb.trustmark

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.config.TrustMarkProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Cached TrustMarkResource payload with HTTP cache metadata. */
data class CachedTrustMarkResource(
    val payload: TrustMarkResourcePayload,
    val fetchedAt: Instant,
    val cacheSource: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val expiresAt: Instant,
)

/** Abstraction for fetching and invalidating cached TrustMarkResource data. */
interface TrustMarkResourceProvider {
    /** Returns the cached or freshly fetched TrustMarkResource, honoring force refresh. */
    fun getResource(forceRefresh: Boolean): CachedTrustMarkResource?

    /** Clears the in-memory TrustMarkResource cache entry. */
    fun invalidate()
}

/** Fetches TrustMarkResource JSON over HTTP with ETag revalidation and TTL caching. */
@Component
class TrustMarkResourceClient(
    private val properties: TrustMarkProperties,
    private val objectMapper: ObjectMapper,
) : TrustMarkResourceProvider {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val cache = AtomicReference<CachedTrustMarkResource?>(null)

    /** Returns a valid cached resource or fetches a new one when expired or forced. */
    override fun getResource(forceRefresh: Boolean): CachedTrustMarkResource? {
        val url = properties.trustMarkResourceUrl.trim()
        if (url.isBlank()) return null
        val cached = cache.get()
        if (!forceRefresh && cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached
        }
        return fetch(url, cached?.takeIf { !forceRefresh })
    }

    /** Drops the cached TrustMarkResource so the next read refetches remotely. */
    override fun invalidate() {
        cache.set(null)
    }

    /** Performs conditional GET against the remote TrustMarkResource URL. */
    private fun fetch(url: String, previous: CachedTrustMarkResource?): CachedTrustMarkResource? {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .header("Accept", "application/json")
        previous?.etag?.let { builder.header("If-None-Match", it) }
        previous?.lastModified?.let { builder.header("If-Modified-Since", it) }

        val response = runCatching {
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }.getOrElse { ex ->
            logger.warn("event=trustmark.fetch.failed url={} reason={}", url, ex.message)
            return previous
        }

        if (response.statusCode() == 304 && previous != null) {
            val refreshed = previous.copy(
                fetchedAt = Instant.now(),
                cacheSource = "http-304",
                expiresAt = resolveExpiry(response, previous.expiresAt),
            )
            cache.set(refreshed)
            return refreshed
        }

        if (response.statusCode() !in 200..299) {
            logger.warn("event=trustmark.fetch.rejected url={} status={}", url, response.statusCode())
            return previous
        }

        val body = response.body()?.trim().orEmpty()
        if (body.isBlank()) return previous

        val payload = runCatching {
            objectMapper.readValue(body, TrustMarkResourcePayload::class.java)
        }.getOrElse { ex ->
            logger.warn("event=trustmark.parse.failed url={} reason={}", url, ex.message)
            return previous
        }

        val entry = CachedTrustMarkResource(
            payload = payload,
            fetchedAt = Instant.now(),
            cacheSource = if (previous == null) "http-fetch" else "http-revalidate",
            etag = response.headers().firstValue("ETag").orElse(previous?.etag),
            lastModified = response.headers().firstValue("Last-Modified").orElse(previous?.lastModified),
            expiresAt = resolveExpiry(response, Instant.now().plusSeconds(properties.cacheTtlSeconds)),
        )
        cache.set(entry)
        return entry
    }

    /** Derives cache expiry from Cache-Control max-age or falls back to the configured TTL. */
    private fun resolveExpiry(response: HttpResponse<String>, fallback: Instant): Instant {
        val cacheControl = response.headers().firstValue("Cache-Control").orElse(null) ?: return fallback
        val maxAge = CACHE_MAX_AGE_REGEX.find(cacheControl)?.groupValues?.get(1)?.toLongOrNull()
        return if (maxAge != null && maxAge > 0) {
            Instant.now().plusSeconds(maxAge)
        } else {
            fallback
        }
    }

    companion object {
        private val CACHE_MAX_AGE_REGEX = Regex("""max-age=(\d+)""", RegexOption.IGNORE_CASE)
    }
}
