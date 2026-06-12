package di.swallet.wpb.presentation.trust

import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.config.OpsProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.trust.core.TrustSnapshot
import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import di.swallet.wpb.trust.core.TrustSnapshotResolver
import di.swallet.wpb.trust.core.TrustedEntity
import di.swallet.wpb.trust.lote.LoteTrustParser
import di.swallet.wpb.trust.lote.LoteTrustSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
class TrustSnapshotService(
    private val properties: OpenId4VpProperties,
    private val opsProperties: OpsProperties,
    private val certificateChainValidator: CertificateChainValidator,
    private val loteTrustParser: LoteTrustParser,
) : TrustSnapshotResolver {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cacheRef = AtomicReference<TrustSnapshot?>()
    private val refreshInProgress = AtomicBoolean(false)
    private val consecutiveRefreshFailures = java.util.concurrent.atomic.AtomicInteger(0)

    fun cachedSnapshot(): TrustSnapshot? = cacheRef.get()

    fun health(): TrustSnapshotHealth {
        val snapshot = cacheRef.get()
        val failures = consecutiveRefreshFailures.get()
        if (snapshot == null) {
            return TrustSnapshotHealth(
                status = TrustSnapshotHealthStatus.DOWN,
                loadedAt = null,
                ageSeconds = null,
                consecutiveFailures = failures,
                reason = "no trust snapshot loaded",
            )
        }
        val ageSeconds = Duration.between(snapshot.loadedAt, Instant.now()).seconds.coerceAtLeast(0)
        val expired = isSnapshotExpired(snapshot)
        val threshold = opsProperties.trustSnapshotFailureThreshold.coerceAtLeast(1)
        val status = when {
            failures >= threshold && expired -> TrustSnapshotHealthStatus.DOWN
            expired -> TrustSnapshotHealthStatus.DEGRADED
            else -> TrustSnapshotHealthStatus.UP
        }
        return TrustSnapshotHealth(
            status = status,
            loadedAt = snapshot.loadedAt,
            ageSeconds = ageSeconds,
            consecutiveFailures = failures,
            reason = when (status) {
                TrustSnapshotHealthStatus.UP -> null
                TrustSnapshotHealthStatus.DEGRADED -> "trust snapshot exceeded max age; serving cached material"
                TrustSnapshotHealthStatus.DOWN -> "trust snapshot unavailable after repeated refresh failures"
            },
        )
    }

    override fun currentAvailability(): TrustSnapshotAvailability {
        val current = cacheRef.get()
        if (current == null) {
            return refresh()
        }
        if (isSnapshotExpired(current)) {
            logger.warn("event=trust.snapshot.expired source={} loadedAt={}", current.source, current.loadedAt)
            return refresh()
        }
        if (shouldRefreshRemote(current)) {
            refresh()
        }
        val latest = cacheRef.get()
        return if (latest != null && !isSnapshotExpired(latest)) {
            TrustSnapshotAvailability.Available(latest)
        } else {
            TrustSnapshotAvailability.Unavailable("no valid trust snapshot available")
        }
    }

    fun refresh(): TrustSnapshotAvailability {
        if (!refreshInProgress.compareAndSet(false, true)) {
            val cached = cacheRef.get()
            return if (cached != null && !isSnapshotExpired(cached)) {
                TrustSnapshotAvailability.Available(cached)
            } else {
                TrustSnapshotAvailability.Unavailable("trust snapshot refresh already in progress")
            }
        }
        val hadCached = cacheRef.get() != null
        return try {
            runCatching { loadSnapshot() }
                .fold(
                    onSuccess = { snapshot ->
                        when {
                            snapshot == null -> {
                                TrustSnapshotAvailability.Unavailable(
                                    "no trust data configured for source mode '${properties.trust.sourceModeNormalized()}'",
                                )
                            }
                            isEffectivelyEmpty(snapshot) -> {
                                TrustSnapshotAvailability.Unavailable(
                                    "trust snapshot is empty (no entities and no anchors)",
                                )
                            }
                            else -> {
                                cacheRef.set(snapshot)
                                consecutiveRefreshFailures.set(0)
                                val eventName = if (hadCached) "trust.snapshot.refreshed" else "trust.snapshot.loaded"
                                logger.info(
                                    "event={} source={} entities={} anchors={}",
                                    eventName,
                                    snapshot.source,
                                    snapshot.entities.size,
                                    snapshot.trustAnchors.size,
                                )
                                TrustSnapshotAvailability.Available(snapshot)
                            }
                        }
                    },
                    onFailure = { ex ->
                        consecutiveRefreshFailures.incrementAndGet()
                        logger.warn("event=trust.snapshot.refresh_failed reason={}", ex.message)
                        val cached = cacheRef.get()
                        if (cached != null && !isSnapshotExpired(cached)) {
                            logger.info("event=trust.snapshot.stale_fallback source={} loadedAt={}", cached.source, cached.loadedAt)
                            TrustSnapshotAvailability.Available(cached)
                        } else {
                            TrustSnapshotAvailability.Unavailable("trust snapshot refresh failed: ${ex.message}")
                        }
                    },
                )
        } finally {
            refreshInProgress.set(false)
        }
    }

    fun refreshRemoteIfConfigured() {
        val mode = properties.trust.sourceModeNormalized()
        if (mode == "remote" || mode == "hybrid") {
            val remoteUrl = properties.trust.remoteTrustUrl.trim()
            if (remoteUrl.isNotBlank()) {
                refresh()
            }
        }
    }

    private fun loadSnapshot(): TrustSnapshot? {
        val mode = properties.trust.sourceModeNormalized()
        val now = Instant.now()
        val local = if (mode == "file" || mode == "hybrid") loadLocalSnapshot(now) else null
        val remote = if (mode == "remote" || mode == "hybrid") {
            runCatching { loadRemoteSnapshot(now) }.getOrElse { ex ->
                if (mode == "remote") throw ex
                logger.warn("remote trust fetch failed in hybrid mode: {}", ex.message)
                null
            }
        } else {
            null
        }

        return when (mode) {
            "file" -> local
            "remote" -> remote
            "hybrid" -> mergeSnapshots(local, remote, now)
            else -> throw IllegalArgumentException("unsupported trust source mode '$mode'")
        }
    }

    private fun loadLocalSnapshot(now: Instant): TrustSnapshot? {
        val localVerifiersPath = properties.trust.localVerifiersPath.trim()
        val localAnchorPaths = properties.trust.localTrustAnchorPemPaths()
        if (localVerifiersPath.isBlank() && localAnchorPaths.isEmpty()) return null

        val localPayload = localVerifiersPath.takeIf { it.isNotBlank() }?.let(certificateChainValidator::readPath)
        val localDocument = localPayload?.let(loteTrustParser::parseDocument)
        val anchorsFromPaths = certificateChainValidator.loadTrustAnchors(localAnchorPaths).map { it.trustedCert }
        val anchorsFromDocument = localDocument?.trustAnchorsPem?.flatMap(certificateChainValidator::parsePemCertificates).orEmpty()
        val anchors = dedupeAnchors(anchorsFromPaths + anchorsFromDocument)

        return loteTrustParser.toTrustSnapshot(
            document = localDocument ?: di.swallet.wpb.trust.lote.LoteTrustDocument(emptyList()),
            source = LoteTrustSource.LOCAL,
            loadedAt = now,
            trustAnchors = anchors,
        )
    }

    private fun loadRemoteSnapshot(now: Instant): TrustSnapshot? {
        val url = properties.trust.remoteTrustUrl.trim()
        if (url.isBlank()) return null
        val payload = fetchRemote(url)
        val document = loteTrustParser.parseDocument(payload)
        val anchors = document.trustAnchorsPem.flatMap(certificateChainValidator::parsePemCertificates)
        return loteTrustParser.toTrustSnapshot(
            document = document,
            source = LoteTrustSource.REMOTE,
            loadedAt = now,
            trustAnchors = dedupeAnchors(anchors),
        )
    }

    private fun mergeSnapshots(
        local: TrustSnapshot?,
        remote: TrustSnapshot?,
        now: Instant,
    ): TrustSnapshot? {
        if (local == null && remote == null) return null
        if (local == null) return remote
        if (remote == null) return local

        // Deterministic precedence: remote overrides local on duplicate entity IDs.
        val entities = LinkedHashMap<String, TrustedEntity>()
        entities.putAll(local.entities)
        entities.putAll(remote.entities)

        val uniqueAnchors = LinkedHashMap<String, X509Certificate>()
        (local.trustAnchors + remote.trustAnchors).forEach { cert ->
            val key = cert.encoded.joinToString("") { "%02X".format(it) }
            uniqueAnchors[key] = cert
        }
        val mergedValidUntil = listOfNotNull(local.validUntil, remote.validUntil).minOrNull()
        return TrustSnapshot(
            trustAnchors = dedupeAnchors(uniqueAnchors.values.toList()),
            entities = entities,
            source = LoteTrustSource.HYBRID.label,
            loadedAt = now,
            validUntil = mergedValidUntil,
        )
    }

    private fun fetchRemote(url: String): String {
        validateRemoteUrlPolicy(url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = properties.trust.remoteConnectTimeoutMs.toInt()
            readTimeout = properties.trust.remoteReadTimeoutMs.toInt()
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        val contentType = conn.contentType?.lowercase().orEmpty()
        if (contentType.isNotBlank() && !contentType.contains("json")) {
            throw IllegalStateException("remote trust source returned unexpected content-type '$contentType'")
        }
        conn.inputStream.bufferedReader().use { return it.readText() }
    }

    private fun shouldRefreshRemote(snapshot: TrustSnapshot): Boolean {
        val mode = properties.trust.sourceModeNormalized()
        if (mode != "remote" && mode != "hybrid") return false
        val refreshInterval = Duration.ofSeconds(properties.trust.remoteRefreshIntervalSeconds.coerceAtLeast(1))
        return snapshot.loadedAt.plus(refreshInterval).isBefore(Instant.now())
    }

    private fun isSnapshotExpired(snapshot: TrustSnapshot): Boolean {
        val maxAge = Duration.ofSeconds(properties.trust.maxSnapshotAgeSeconds.coerceAtLeast(1))
        if (snapshot.loadedAt.plus(maxAge).isBefore(Instant.now())) {
            return true
        }
        val validUntil = snapshot.validUntil ?: return false
        return validUntil.isBefore(Instant.now())
    }

    private fun isEffectivelyEmpty(snapshot: TrustSnapshot): Boolean =
        snapshot.entities.isEmpty() && snapshot.trustAnchors.isEmpty()

    private fun dedupeAnchors(certs: List<X509Certificate>): List<X509Certificate> {
        val unique = LinkedHashMap<String, X509Certificate>()
        certs.forEach { cert ->
            val key = cert.encoded.joinToString("") { "%02X".format(it) }
            unique[key] = cert
        }
        return unique.values.toList()
    }

    private fun validateRemoteUrlPolicy(rawUrl: String) {
        val uri = runCatching { URI(rawUrl) }.getOrElse {
            throw IllegalArgumentException("remote trust URL is invalid: ${it.message}")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        require(host.isNotBlank()) { "remote trust URL host is required" }
        if (!properties.demoMode && scheme != "https") {
            throw IllegalStateException("production mode requires HTTPS remote trust URL")
        }
        val allowlist = properties.trust.remoteAllowedHosts()
        if (!properties.demoMode) {
            require(allowlist.isNotEmpty()) { "production mode requires non-empty remote trust host allow-list" }
            if (host !in allowlist) {
                throw IllegalStateException("remote trust host '$host' is not allow-listed")
            }
        } else if (allowlist.isNotEmpty() && host !in allowlist) {
            throw IllegalStateException("demo mode host '$host' is not allow-listed by configured trust policy")
        }
    }
}
