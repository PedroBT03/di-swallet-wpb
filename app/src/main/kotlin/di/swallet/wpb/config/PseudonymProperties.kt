/**
 * Configuration properties for pseudonym credential issuance and limits.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Binds `wpb.pseudonym.*` settings for RP-scoped pseudonym credentials. */
@ConfigurationProperties(prefix = "wpb.pseudonym")
class PseudonymProperties {
    var enabled: Boolean = false
    var maxPerRp: Int = 10
    /** Comma-separated RP IDs (WebAuthn rpId / domain). Empty = any valid rpId when enabled. */
    var allowedRpIds: String = ""
    var userHandleEntropyBytes: Int = 32
    var challengeTtlSeconds: Long = 300
    var logIncludeAliasInExport: Boolean = false

    /**
     * Parses the comma-separated allowed RP id list into a normalized set.
     */
    fun allowedRpIdSet(): Set<String> =
        allowedRpIds.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
}
