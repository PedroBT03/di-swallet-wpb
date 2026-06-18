/**
 * Configuration properties for DPA reporting email templates and fallback contacts.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Binds `wpb.dpa-reporting.*` settings for suspicious-request reporting to supervisory authorities. */
@ConfigurationProperties(prefix = "wpb.dpa-reporting")
class DpaReportProperties {
    var mailSubjectTemplate: String =
        "Reporting an allegedly unlawful or suspicious request received from {dNSName}"

    var mailBodyTemplate: String = """
        Dear Ladies and Gentlemen,

        we have received a request from {dNSName}, which seems to be
        suspicous and potentially unlawful. Therefore we kindly ask you to
        have closer look at the received request provided as attachment of
        the present mail and consider to take further actions, if deemed appropriate.

        Thank you very much in advance for your efforts.

        Best Regards
    """.trimIndent()

    var registryLookupNotice: String =
        "No DPA contact was stored for this presentation. Registry lookup was used to obtain current supervisory authority contacts."

    var noContactNotice: String =
        "No DPA contact channel is available for this presentation."

    var providerFallbackDpa: ProviderFallbackDpa = ProviderFallbackDpa()
}

/** Fallback DPA contact details used when registry lookup returns no contact channel. */
class ProviderFallbackDpa {
    var name: String = ""
    var country: String = ""
    var email: String = ""
    var phone: String = ""
    var formUri: String = ""

    /**
     * Returns true when any fallback DPA field has been configured.
     */
    fun isConfigured(): Boolean =
        name.isNotBlank() || country.isNotBlank() ||
            email.isNotBlank() || phone.isNotBlank() || formUri.isNotBlank()

    /**
     * Returns true when at least one reachable contact channel is configured.
     */
    fun hasContactChannel(): Boolean =
        email.isNotBlank() || phone.isNotBlank() || formUri.isNotBlank()
}
