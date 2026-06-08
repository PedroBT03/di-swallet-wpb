package di.swallet.wpb.datadeletion

import org.springframework.stereotype.Component

enum class DeletionContactChannel {
    WEB,
    EMAIL,
    PHONE,
}

data class ClassifiedDeletionContact(
    val channel: DeletionContactChannel,
    val value: String,
)

@Component
class SupportUriClassifier {
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

    fun classifyAll(values: List<String>): List<ClassifiedDeletionContact> =
        values.mapNotNull { classify(it) }

    companion object {
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val PHONE_REGEX = Regex("^[+0-9][0-9\\s().-]{5,}$")
    }
}
