package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

/**
 * Typed configuration for the OID4VCI / Phase 2 issuance subsystem.
 *
 * Binds `wpb.openid4vci.*` keys so the IDE and Spring Boot configuration
 * processor recognise them as first-class settings.
 *
 * Registered via `@EnableConfigurationProperties` in `WpbApplication` to
 * avoid the double-bean registration that `@Component` here would cause.
 */
@ConfigurationProperties(prefix = "wpb.openid4vci")
class OpenId4VciProperties {
    /** Activates the deterministic in-process simulator adapter. */
    var demoMode: Boolean = true

    /** Lifetime of an issuance session in seconds. */
    var sessionTtlSeconds: Long = 1800

    @NestedConfigurationProperty
    var sdk: SdkProperties = SdkProperties()

    @NestedConfigurationProperty
    var trust: TrustProperties = TrustProperties()

    @NestedConfigurationProperty
    var policy: PolicyProperties = PolicyProperties()

    @NestedConfigurationProperty
    var simulator: SimulatorProperties = SimulatorProperties()

    @NestedConfigurationProperty
    var wia: WiaProperties = WiaProperties()

    class SdkProperties {
        /** Optional hint of the credential issuer identifier (used by the real SDK adapter). */
        var credentialIssuerId: String = ""

        /** DPoP support mode forwarded to the EUDI SDK config: `supported` | `required` | `disabled`. */
        var dpopMode: String = "supported"

        /** Metadata policy mode: `preferSigned` | `requireSigned` | `ignoreSigned`. */
        var metadataPolicy: String = "preferSigned"

        /** PKCE is always enforced by the wallet (RFC 9126/7636); kept here for visibility. */
        var pkceRequired: Boolean = true

        /** Use PAR automatically when the issuer advertises it. */
        var useParWhenSupported: Boolean = true
    }

    class TrustProperties {
        /** Comma-separated allow-list of issuer identifiers. Empty = depends on `demo-mode`. */
        var allowedIssuerIds: String = ""

        fun allowedIssuerIds(): Set<String> = allowedIssuerIds
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    class PolicyProperties {
        /** Allow `mso_mdoc` credentials. Defaults to false (deferred to Phase 7). */
        var allowMdoc: Boolean = false
    }

    class SimulatorProperties {
        /** When true, every credential request resolves to a deferred outcome. */
        var alwaysDefer: Boolean = false

        /**
         * Number of poll iterations before the simulator returns issued credentials.
         * Lower values keep deferred tests fast.
         */
        var deferredPollsBeforeIssue: Int = 1
    }

    class WiaProperties {
        /** Enable WIA sub-context processing in issuance flow. */
        var enabled: Boolean = true

        /** WIA JWT technical TTL in seconds (must remain < 24h). */
        var tokenTtlSeconds: Long = 6 * 60 * 60

        /** Minimum status maintenance period in days. */
        var minStatusMaintenanceDays: Long = 31

        /** Reuse status entry per issuer; default false for privacy. */
        var reusePerIssuer: Boolean = false

        /** Wallet solution identity claims included in WIA. */
        var walletName: String = "DI-Swallet-WPB"
        var walletVersion: String = "0.1.0"
        var walletLink: String = ""
        var walletSolutionCertificationInformation: String = "thesis-mvp-not-certified"

        /** Simplified x5c transport value for MVP demo mode. */
        var signingX5c: String = ""

        /** Retry semantics for nonce mismatch / expired WIA. */
        var maxNonceMismatchRetries: Int = 1
        var maxExpiredRetries: Int = 1
    }
}
