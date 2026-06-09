package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

@ConfigurationProperties(prefix = "wpb.consent")
class ConsentProperties {
    /** Master switch for presentation consent UX (OIA_06, RPA_07–10). */
    var enabled: Boolean = true

    /** Require FIDO2 on POST /openid4vp/consent and POST /openid4vci/consent. */
    var requireFido2OnSubmit: Boolean = true

    /** Enforce RPA_10a all-or-nothing across all DCQL query ids. */
    var enforceAllOrNothing: Boolean = true

    /** Require explicit credential selection (OIA_10/11); disables auto-select. */
    var requireExplicitCredentialChoice: Boolean = true

    @NestedConfigurationProperty
    var issuance: IssuanceConsentProperties = IssuanceConsentProperties()

    class IssuanceConsentProperties {
        /** Gate ISSU_11: holder approval before persisting issued credentials. */
        var enabled: Boolean = true

        /** TTL for pending credential payload while awaiting consent (seconds). */
        var pendingTtlSeconds: Long = 600
    }
}
