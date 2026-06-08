package di.swallet.wpb.dpareport

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.datadeletion.DeletionContactChannel
import org.springframework.stereotype.Component
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

enum class DpaActionChannel {
    WEB,
    EMAIL,
    PHONE,
}

data class DpaAction(
    val channel: DpaActionChannel,
    val uri: String,
    val contactValue: String,
)

@Component
class DpaMailTemplateBuilder(
    private val properties: DpaReportProperties,
) {
    fun buildMailto(email: String, dnsName: String): String {
        val subject = properties.mailSubjectTemplate.replace("{dNSName}", dnsName)
        val body = properties.mailBodyTemplate.replace("{dNSName}", dnsName)
        val encodedSubject = UriUtils.encode(subject, StandardCharsets.UTF_8)
        val encodedBody = UriUtils.encode(body, StandardCharsets.UTF_8)
        return "mailto:$email?subject=$encodedSubject&body=$encodedBody"
    }
}

@Component
class DpaActionBuilder(
    private val mailTemplateBuilder: DpaMailTemplateBuilder,
) {
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

    fun firstAction(contacts: ParsedDpaContacts, dnsName: String): DpaAction? =
        buildAll(contacts, dnsName).firstOrNull()

    private fun channelOrder(channel: DeletionContactChannel): Int = when (channel) {
        DeletionContactChannel.WEB -> 0
        DeletionContactChannel.EMAIL -> 1
        DeletionContactChannel.PHONE -> 2
    }
}
