package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

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
        "Trust Mark is not configured. Set wpb.trust-mark.enabled=true and provide TS1 URLs for production."

    fun isConfigured(): Boolean =
        enabled &&
            trustMarkResourceUrl.isNotBlank() &&
            listOfCertifiedWalletsUrl.isNotBlank() &&
            walletSolutionInfoPageUrl.isNotBlank()
}
