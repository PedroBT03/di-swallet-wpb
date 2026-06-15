/**
 * Configuration properties for WPB security policy overrides.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Binds `wpb.security.*` settings for local development security opt-ins. */
@ConfigurationProperties(prefix = "wpb.security")
class SecurityProperties {
    /** Explicit opt-in for documented weak crypto defaults (local dev / CI only). */
    var allowKnownWeakCryptoSecrets: Boolean = false
}
