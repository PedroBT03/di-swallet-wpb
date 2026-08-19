/**
 * Configuration properties for OpenID4VCI credential issuance flows.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

/**
 * Typed configuration for the OID4VCI issuance subsystem.
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

    @NestedConfigurationProperty
    var ka: KaProperties = KaProperties()

    /** EUDI SDK adapter settings forwarded to the real issuance client. */
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

        /**
         * Enforce strict SDK resolution for offer/metadata.
         * Keep enabled in production; disable only for local mock integration tests.
         */
        var strictResolution: Boolean = true
    }

    /** Issuer allow-list and signed metadata validation settings. */
    class TrustProperties {
        /** Comma-separated allow-list of issuer identifiers. Empty = depends on `demo-mode`. */
        var allowedIssuerIds: String = ""

        /** Clock skew applied when validating signed issuer metadata JWT exp/nbf/iat claims. */
        var clockSkewSeconds: Long = 60

        /**
         * Parses the comma-separated allowed issuer id list into a set.
         */
        fun allowedIssuerIds(): Set<String> = allowedIssuerIds
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** Credential format policy gates such as mDoc allowance. */
    class PolicyProperties {
        /** Allow `mso_mdoc` credentials. Defaults to false and is controlled by policy gate. */
        var allowMdoc: Boolean = false
    }

    /** In-process simulator behavior for demo and test issuance flows. */
    class SimulatorProperties {
        /** When true, every credential request resolves to a deferred outcome. */
        var alwaysDefer: Boolean = false

        /**
         * Number of poll iterations before the simulator returns issued credentials.
         * Lower values keep deferred tests fast.
         */
        var deferredPollsBeforeIssue: Int = 1
    }

    /** Wallet Instance Attestation token generation and status maintenance settings. */
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

        /** Optional x5c chain in WIA header (comma-separated DER base64 or PEM blocks). */
        var signingX5c: String = ""

        /** Retry semantics for nonce mismatch / expired WIA. */
        var maxNonceMismatchRetries: Int = 1
        var maxExpiredRetries: Int = 1

        /**
         * Parses configured WIA signing certificate material from PEM blocks or comma-separated DER.
         */
        fun signingX5cChain(): List<String> {
            val raw = signingX5c.trim()
            if (raw.isBlank()) return emptyList()
            if (raw.contains("-----BEGIN CERTIFICATE-----")) {
                val pemRegex = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
                return pemRegex.findAll(raw)
                    .map { it.groupValues[1] }
                    .map { it.replace("\\s".toRegex(), "") }
                    .filter { it.isNotBlank() }
                    .toList()
            }
            return raw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
    }

    /** Key Attestation token generation, trust policy, and signing chain settings. */
    class KaProperties {
        /** Enable key attestation sub-context processing for device-bound issuance. */
        var enabled: Boolean = true

        /** KA JWT technical TTL in seconds. */
        var tokenTtlSeconds: Long = 6 * 60 * 60

        /** Minimum key storage status maintenance period in days. */
        var minStatusMaintenanceDays: Long = 31

        /** Reuse key attestation per issuer; defaults false for privacy. */
        var reusePerIssuer: Boolean = false

        /** Key storage and certification claims included in KA payload. */
        var keyStorage: String = "iso_18045_high"
        var userAuthentication: String = "iso_18045_high"
        var certificationScheme: String = "eidas"
        var certificationAssuranceLevel: String = "high"
        var certificationInfo: String = "thesis-mvp-not-certified"

        /** Issuer identity used in KA `iss` claim. */
        var issuer: String = "did:web:wpb.local"

        /** Optional x5c chain in KA header (comma-separated DER base64 or PEM blocks). */
        var signingX5c: String = ""

        /**
         * When true, issuance fails unless `signing-x5c` is configured.
         * Recommended for production deployments (`demo-mode=false`).
         */
        var requireConfiguredSigningChain: Boolean = false

        /**
         * When true outside demo-mode, relaxed trust is upgraded to strict unless
         * fingerprint or trust-anchor mitigations are configured.
         */
        var enforceProductionTrustPolicy: Boolean = false

        /** Trust policy for attestation certificates (MVP allow-list model). */
        var requireX5c: Boolean = false
        var allowedX5cFingerprints: String = ""
        var trustMode: String = "relaxed"
        var trustAnchorPemPaths: String = ""

        /**
         * Parses the comma-separated allowed attestation certificate fingerprint list into a set.
         */
        fun allowedX5cFingerprints(): Set<String> = allowedX5cFingerprints
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        /**
         * Parses the comma-separated KA trust anchor PEM paths into a list.
         */
        fun trustAnchorPemPaths(): List<String> = trustAnchorPemPaths
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        /**
         * Parses configured KA signing certificate material from PEM blocks or comma-separated DER.
         */
        fun signingX5cChain(): List<String> {
            val raw = signingX5c.trim()
            if (raw.isBlank()) return emptyList()
            if (raw.contains("-----BEGIN CERTIFICATE-----")) {
                val pemRegex = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
                return pemRegex.findAll(raw)
                    .map { it.groupValues[1] }
                    .map { it.replace("\\s".toRegex(), "") }
                    .filter { it.isNotBlank() }
                    .toList()
            }
            return raw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
    }
}
