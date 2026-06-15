/**
 * Builds web, email, and phone action URIs for contacting a DPA about a relying party.
 */

package di.swallet.wpb.dpareport

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.datadeletion.DeletionContactChannel
import org.springframework.stereotype.Component
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/** Supported channels for reaching a DPA from the wallet. */
enum class DpaActionChannel {
    WEB,
    EMAIL,
    PHONE,
}

/** One actionable DPA contact with its launch URI and raw contact value. */
data class DpaAction(
    val channel: DpaActionChannel,
    val uri: String,
    val contactValue: String,
)

/** Fills mailto subject and body templates with the reported RP DNS name. */
@Component
class DpaMailTemplateBuilder(
    private val properties: DpaReportProperties,
) {
    /** Builds a mailto URI with configured subject and body templates. */
    fun buildMailto(email: String, dnsName: String): String {
        val subject = properties.mailSubjectTemplate.replace("{dNSName}", dnsName)
        val body = properties.mailBodyTemplate.replace("{dNSName}", dnsName)
        val encodedSubject = UriUtils.encode(subject, StandardCharsets.UTF_8)
        val encodedBody = UriUtils.encode(body, StandardCharsets.UTF_8)
        return "mailto:$email?subject=$encodedSubject&body=$encodedBody"
    }
}

/** Converts parsed DPA contacts into ordered web, email, and phone actions. */
@Component
class DpaActionBuilder(
    private val mailTemplateBuilder: DpaMailTemplateBuilder,
) {
    /** Builds all actionable URIs for the given contacts, preferring web before email and phone. */
    fun buildAll(contacts: ParsedDpaContacts, dnsName: String): List<DpaAction> {
        val actions = mutableListOf<DpaAction>()
        contacts.contacts
            .sortedBy { channelOrder(it.channel) }
            .forEach { contact ->
                when (contact.channel) {
                    DeletionContactChannel.WEB -> actions.add(
                        DpaAction(DpaActionChannel.WEB, contact.value, contact.value),
                    )
                    DeletionContactChannel.EMAIL -> actions.add(
                        DpaAction(
                            DpaActionChannel.EMAIL,
                            mailTemplateBuilder.buildMailto(contact.value, dnsName),
                            contact.value,
                        ),
                    )
                    DeletionContactChannel.PHONE -> actions.add(
                        DpaAction(DpaActionChannel.PHONE, "tel:${contact.value}", contact.value),
                    )
                }
            }
        return actions
    }

    /** Returns the first actionable contact, if any. */
    fun firstAction(contacts: ParsedDpaContacts, dnsName: String): DpaAction? =
        buildAll(contacts, dnsName).firstOrNull()

    /** Defines presentation order for DPA contact channels. */
    private fun channelOrder(channel: DeletionContactChannel): Int = when (channel) {
        DeletionContactChannel.WEB -> 0
        DeletionContactChannel.EMAIL -> 1
        DeletionContactChannel.PHONE -> 2
    }
}
