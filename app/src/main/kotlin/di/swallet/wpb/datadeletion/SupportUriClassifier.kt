/**
 * Classifies support URIs and raw contact strings into deletion and DPA report channels.
 */

package di.swallet.wpb.datadeletion

import org.springframework.stereotype.Component

/** Contact channel type used for deletion and DPA reporting actions. */
enum class DeletionContactChannel {
    WEB,
    EMAIL,
    PHONE,
}

/** One support contact value with its detected channel type. */
data class ClassifiedDeletionContact(
    val channel: DeletionContactChannel,
    val value: String,
)

/** Detects whether a raw string is a web form, email address, or phone number. */
@Component
class SupportUriClassifier {
    /** Classifies a single raw contact string, returning null for blank input. */
    fun classify(raw: String): ClassifiedDeletionContact? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("mailto:") -> ClassifiedDeletionContact(
                DeletionContactChannel.EMAIL,
                trimmed.removePrefix("mailto:").removePrefix("MAILTO:"),
            )
            lower.startsWith("tel:") -> ClassifiedDeletionContact(
                DeletionContactChannel.PHONE,
                trimmed.removePrefix("tel:").removePrefix("TEL:"),
            )
            lower.startsWith("http://") || lower.startsWith("https://") ->
                ClassifiedDeletionContact(DeletionContactChannel.WEB, trimmed)
            EMAIL_REGEX.matches(trimmed) -> ClassifiedDeletionContact(DeletionContactChannel.EMAIL, trimmed)
            PHONE_REGEX.matches(trimmed) -> ClassifiedDeletionContact(DeletionContactChannel.PHONE, trimmed)
            else -> ClassifiedDeletionContact(DeletionContactChannel.WEB, trimmed)
        }
    }

    /** Classifies every non-blank value in the input list. */
    fun classifyAll(values: List<String>): List<ClassifiedDeletionContact> =
        values.mapNotNull { classify(it) }

    companion object {
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val PHONE_REGEX = Regex("^[+0-9][0-9\\s().-]{5,}$")
    }
}
