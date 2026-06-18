/**
 * Configuration properties for GDPR data deletion request email templates.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Binds `wpb.data-deletion.*` settings for holder-initiated erasure request emails. */
@ConfigurationProperties(prefix = "wpb.data-deletion")
class DataDeletionRequestProperties {
    var mailSubject: String =
        "Request for erasure of personal data under Article 17 GDPR (EU) 2016/679"

    var mailBodyAllTemplate: String = """
        Dear {rpName},

        I request the erasure of all personal data that was previously provided to you through my European Digital Identity Wallet{presentationTime}, in accordance with Article 17 of Regulation (EU) 2016/679.

        Please confirm receipt and processing of this request.

        Regards
    """.trimIndent()

    var mailBodyClaimsTemplate: String = """
        Dear {rpName},

        I request the erasure of the following personal data attributes that were previously provided to you through my European Digital Identity Wallet{presentationTime}, in accordance with Article 17 of Regulation (EU) 2016/679:

        {claimLines}

        Please confirm receipt and processing of this request.

        Regards
    """.trimIndent()

    var registryLookupNotice: String =
        "No deletion contact was stored for this presentation. Registry lookup was used to obtain current support contacts."

    var fallbackNotice: String =
        "No deletion contact was stored for this presentation. Provider-configured fallback contacts were used."

    var noContactNotice: String =
        "No contact channel is available for this relying party."

    var providerFallbackRp: ProviderFallbackRpDeletion = ProviderFallbackRpDeletion()
}

/** Fallback RP deletion contacts when the presentation log and registry provide none. */
class ProviderFallbackRpDeletion {
    var country: String = ""
    var email: String = ""
    var phone: String = ""
    var webUri: String = ""

    /** True when any fallback field is configured. */
    fun isConfigured(): Boolean =
        country.isNotBlank() || email.isNotBlank() || phone.isNotBlank() || webUri.isNotBlank()

    /** True when at least one actionable deletion channel is configured. */
    fun hasDeletionChannel(): Boolean =
        email.isNotBlank() || phone.isNotBlank() || webUri.isNotBlank()
}
