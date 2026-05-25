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
    }

    class SessionProperties {
        /** Presentation session TTL in seconds. */
        var ttlSeconds: Long = 600
    }
}
