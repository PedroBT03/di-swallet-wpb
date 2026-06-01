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

    class SessionProperties {
        /** Presentation session TTL in seconds. */
        var ttlSeconds: Long = 600
    }
}
