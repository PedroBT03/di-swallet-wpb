package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wpb.status-list")
class StatusListProperties {
    /** Fixed bitstring capacity for random index allocation (VCR_17 / TS3 herd privacy). */
    var capacity: Int = 131_072

    /** PEM path for the dedicated Status List signing key (separate from WIA/KA). */
    var signingKeyPemPath: String = "classpath:status-list/dev-signing-key.pem"

    var autoGenerateSigningKeyIfMissing: Boolean = true

    /** JWT time-to-live for published Token Status Lists (seconds). */
    var jwtTtlSeconds: Long = 86_400

    /** Cron for background revocation sync (VCR_19). */
    var syncCron: String = "0 0 * * * *"

    /** Base URL for status list credential references (no trailing slash). */
    var publicBaseUrl: String = "http://localhost:8080/api/v1/wallet/status-lists"
}
