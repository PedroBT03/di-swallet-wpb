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

data class CachedTrustMarkResource(
    val payload: TrustMarkResourcePayload,
    val fetchedAt: Instant,
    val cacheSource: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val expiresAt: Instant,
)

interface TrustMarkResourceProvider {
    fun getResource(forceRefresh: Boolean): CachedTrustMarkResource?
    fun invalidate()
}

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

    override fun getResource(forceRefresh: Boolean): CachedTrustMarkResource? {
        val url = properties.trustMarkResourceUrl.trim()
        if (url.isBlank()) return null
        val cached = cache.get()
        if (!forceRefresh && cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached
        }
        return fetch(url, cached?.takeIf { !forceRefresh })
    }

    override fun invalidate() {
        cache.set(null)
    }

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
