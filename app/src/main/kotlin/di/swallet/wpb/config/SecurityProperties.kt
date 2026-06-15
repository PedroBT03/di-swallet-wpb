package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wpb.security")
class SecurityProperties {
    /** Explicit opt-in for documented weak crypto defaults (local dev / CI only). */
    var allowKnownWeakCryptoSecrets: Boolean = false
}
