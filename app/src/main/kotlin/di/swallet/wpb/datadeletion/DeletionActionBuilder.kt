/**
 * Builds web, email, and phone action URIs for GDPR deletion requests to relying parties.
 */

package di.swallet.wpb.datadeletion

import di.swallet.wpb.config.DataDeletionRequestProperties
import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import org.springframework.stereotype.Component
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/** Supported channels for contacting a relying party about data deletion. */
enum class DeletionActionChannel {
    WEB,
    EMAIL,
    PHONE,
}

/** One actionable deletion contact with its launch URI. */
data class DeletionAction(
    val channel: DeletionActionChannel,
    val uri: String,
)

/** Fills mailto templates with RP name, presentation time, and selected claims. */
@Component
class DeletionMailTemplateBuilder(
    private val properties: DataDeletionRequestProperties,
) {
    /** Builds a mailto URI with configured deletion request subject and body. */
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

    /** Chooses the all-claims or per-claim mail body template and substitutes placeholders. */
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

/** Converts parsed deletion contacts into ordered web, email, and phone actions. */
@Component
class DeletionActionBuilder(
    private val mailTemplateBuilder: DeletionMailTemplateBuilder,
) {
    /** Builds all actionable URIs for the given contacts and claim selection. */
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

    /** Defines presentation order for deletion contact channels. */
    private fun channelOrder(channel: DeletionContactChannel): Int = when (channel) {
        DeletionContactChannel.WEB -> 0
        DeletionContactChannel.EMAIL -> 1
        DeletionContactChannel.PHONE -> 2
    }
}
