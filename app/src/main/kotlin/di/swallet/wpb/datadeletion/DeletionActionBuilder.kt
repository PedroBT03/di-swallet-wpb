package di.swallet.wpb.datadeletion

import di.swallet.wpb.config.DataDeletionRequestProperties
import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import org.springframework.stereotype.Component
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

enum class DeletionActionChannel {
    WEB,
    EMAIL,
    PHONE,
}

data class DeletionAction(
    val channel: DeletionActionChannel,
    val uri: String,
)

@Component
class DeletionMailTemplateBuilder(
    private val properties: DataDeletionRequestProperties,
) {
    fun buildMailto(
        email: String,
        rpName: String,
        presentationTime: String?,
        claims: List<Ts10ClaimInfo>,
        deleteAllPresented: Boolean,
    ): String {
        val subject = properties.mailSubject
        val body = buildBody(rpName, presentationTime, claims, deleteAllPresented)
        val encodedSubject = UriUtils.encode(subject, StandardCharsets.UTF_8)
        val encodedBody = UriUtils.encode(body, StandardCharsets.UTF_8)
        return "mailto:$email?subject=$encodedSubject&body=$encodedBody"
    }

    private fun buildBody(
        rpName: String,
        presentationTime: String?,
        claims: List<Ts10ClaimInfo>,
        deleteAllPresented: Boolean,
    ): String {
        val whenClause = presentationTime?.let { " on $it" }.orEmpty()
        return if (deleteAllPresented) {
            properties.mailBodyAllTemplate
                .replace("{rpName}", rpName)
                .replace("{presentationTime}", whenClause)
        } else {
            val claimLines = claims.joinToString("\n") { info ->
                "- ${info.credentialIdentifier}: ${info.claims.joinToString(", ")}"
            }
            properties.mailBodyClaimsTemplate
                .replace("{rpName}", rpName)
                .replace("{presentationTime}", whenClause)
                .replace("{claimLines}", claimLines)
        }
    }
}

@Component
class DeletionActionBuilder(
    private val mailTemplateBuilder: DeletionMailTemplateBuilder,
) {
    fun buildAll(
        contacts: ParsedDeletionContacts,
        rpName: String,
        presentationTime: String?,
        claims: List<Ts10ClaimInfo>,
        deleteAllPresented: Boolean,
    ): List<DeletionAction> {
        val actions = mutableListOf<DeletionAction>()
        contacts.contacts
            .sortedBy { channelOrder(it.channel) }
            .forEach { contact ->
                when (contact.channel) {
                    DeletionContactChannel.WEB -> actions.add(
                        DeletionAction(DeletionActionChannel.WEB, contact.value),
                    )
                    DeletionContactChannel.EMAIL -> actions.add(
                        DeletionAction(
                            DeletionActionChannel.EMAIL,
                            mailTemplateBuilder.buildMailto(
                                email = contact.value,
                                rpName = rpName,
                                presentationTime = presentationTime,
                                claims = claims,
                                deleteAllPresented = deleteAllPresented,
                            ),
                        ),
                    )
                    DeletionContactChannel.PHONE -> actions.add(
                        DeletionAction(DeletionActionChannel.PHONE, "tel:${contact.value}"),
                    )
                }
            }
        return actions
    }

    private fun channelOrder(channel: DeletionContactChannel): Int = when (channel) {
        DeletionContactChannel.WEB -> 0
        DeletionContactChannel.EMAIL -> 1
        DeletionContactChannel.PHONE -> 2
    }
}
