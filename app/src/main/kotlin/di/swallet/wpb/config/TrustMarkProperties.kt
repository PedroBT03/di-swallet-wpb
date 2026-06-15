/**
 * Configuration properties for TS1 wallet trust mark publication.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Binds `wpb.trust-mark.*` settings for trust mark metadata and admin refresh. */
@ConfigurationProperties(prefix = "wpb.trust-mark")
class TrustMarkProperties {
    var enabled: Boolean = false
    var trustMarkResourceUrl: String = ""
    var listOfCertifiedWalletsUrl: String = ""
    var walletSolutionInfoPageUrl: String = ""
    var walletSolutionId: String = ""
    var cacheTtlSeconds: Long = 3600
    var defaultLanguage: String = "en"
    var allowAdminRefresh: Boolean = false
    var disabledNotice: String =
        "Trust Mark is not configured. Set wpb.trust-mark.enabled=true and provide trust mark URLs for production."

    /**
     * Returns true when trust mark publication is enabled and all required URLs are set.
     */
    fun isConfigured(): Boolean =
        enabled &&
            trustMarkResourceUrl.isNotBlank() &&
            listOfCertifiedWalletsUrl.isNotBlank() &&
            walletSolutionInfoPageUrl.isNotBlank()
}
