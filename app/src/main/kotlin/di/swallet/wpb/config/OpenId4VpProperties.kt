package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Typed configuration for OpenID4VP / Phase 1 presentation flows.
 *
 * Binds `wpb.openid4vp.*` keys from [application.properties] so the IDE and
 * Spring Boot configuration processor recognise them as first-class settings.
 */
@Component
@ConfigurationProperties(prefix = "wpb.openid4vp")
class OpenId4VpProperties {
    /** Enables demo-only resolver/dispatch paths for the local verifier emulator. */
    var demoMode: Boolean = false

    var trust: TrustProperties = TrustProperties()

    var registry: RegistryProperties = RegistryProperties()

    var session: SessionProperties = SessionProperties()

    class TrustProperties {
        /** Comma-separated allow-list of verifier `client_id` values (empty = open). */
        var allowedClientIds: String = ""

        /** Source mode for trust material: `file` | `remote` | `hybrid`. */
        var sourceMode: String = "hybrid"

        /** Optional JSON file with trusted verifier rules (classpath:/ or file path). */
        var localVerifiersPath: String = ""

        /** Optional CSV of PEM trust anchor paths (classpath:/ or file paths). */
        var localTrustAnchorPemPaths: String = ""

        /** Optional remote URL returning trust material JSON. */
        var remoteTrustUrl: String = ""

        /** Comma-separated allow-list of hosts accepted for remote trust fetch in production. */
        var remoteAllowedHosts: String = ""

        /** Remote refresh interval in seconds. */
        var remoteRefreshIntervalSeconds: Long = 900

        /** Remote fetch connect timeout in milliseconds. */
        var remoteConnectTimeoutMs: Long = 3000

        /** Remote fetch read timeout in milliseconds. */
        var remoteReadTimeoutMs: Long = 5000

        /** Maximum accepted trust snapshot age in seconds. */
        var maxSnapshotAgeSeconds: Long = 24 * 60 * 60

        /** Allow fail-open when trust is unavailable only if demo-mode is true. */
        var allowFailOpenInDemoMode: Boolean = true

        fun localTrustAnchorPemPaths(): List<String> = localTrustAnchorPemPaths
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        fun sourceModeNormalized(): String = sourceMode.trim().lowercase()

        fun allowedClientIds(): Set<String> = allowedClientIds
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        fun remoteAllowedHosts(): Set<String> = remoteAllowedHosts
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    class RegistryProperties {
        /** Enables TS5/TS6 RP registry validation in presentation flows. */
        var enabled: Boolean = false

        /** TS version reference used for runtime observability and audit. */
        var specificationVersion: String = "TS5-1.2"

        /** Base URL of the national RP registry API. */
        var baseUrl: String = ""

        /** Comma-separated allow-list of hosts accepted for production registry fetches. */
        var remoteAllowedHosts: String = ""

        /** HTTP connect timeout for registry calls in milliseconds. */
        var connectTimeoutMs: Long = 3000

        /** HTTP read timeout for registry calls in milliseconds. */
        var readTimeoutMs: Long = 5000

        /** Max accepted age for cached registry records in seconds. */
        var maxCacheAgeSeconds: Long = 900

        /** Comma-separated paths to PEM public keys (or certs) used for JWS verification. */
        var verificationKeyPemPaths: String = ""

        /** Whether registry read responses must be signed JWTs (TS5 baseline). */
        var requireSignedResponses: Boolean = true

        /** Whether to require TS5 signed envelope claims: iss, iat, data. */
        var requireSignedEnvelopeFields: Boolean = true

        /** Optional allow-list of accepted registry issuers (`iss` claim). */
        var allowedIssuers: String = ""

        /** Optional expected audience for registry response JWTs. */
        var expectedAudience: String = ""

        /** Clock skew tolerance for JWT temporal claim checks. */
        var clockSkewSeconds: Long = 60

        /** Prefer TS5 check-intended-use endpoint over local inference when reachable. */
        var preferCheckIntendedUseEndpoint: Boolean = true

        fun remoteAllowedHosts(): Set<String> = remoteAllowedHosts
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        fun verificationKeyPemPaths(): List<String> = verificationKeyPemPaths
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        fun allowedIssuers(): Set<String> = allowedIssuers
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    class SessionProperties {
        /** Presentation session TTL in seconds. */
        var ttlSeconds: Long = 600
    }
}
