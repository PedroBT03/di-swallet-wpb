package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wpb.pseudonym")
class PseudonymProperties {
    var enabled: Boolean = false
    var maxPerRp: Int = 10
    /** Comma-separated RP IDs (WebAuthn rpId / domain). Empty = any valid rpId when enabled. */
    var allowedRpIds: String = ""
    var userHandleEntropyBytes: Int = 32
    var challengeTtlSeconds: Long = 300
    var logIncludeAliasInExport: Boolean = false

    fun allowedRpIdSet(): Set<String> =
        allowedRpIds.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
}
