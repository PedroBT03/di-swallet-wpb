package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wpb.wsca")
class WscaSciProperties {
    /**
     * When true, HSM signing/keygen requires an SCI authorization context (FIDO2 or
     * short-lived grant after holder consent). Mirrors the PDF WI→WSCA boundary.
     */
    var enforceSciBoundary: Boolean = true

    /** TTL for SCI grants issued after holder consent (multi-step OID4 flows). */
    var consentGrantTtlSeconds: Long = 900
}
