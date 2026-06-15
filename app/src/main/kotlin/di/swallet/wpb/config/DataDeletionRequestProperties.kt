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
}
