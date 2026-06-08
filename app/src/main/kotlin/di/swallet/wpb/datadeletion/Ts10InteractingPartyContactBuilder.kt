package di.swallet.wpb.datadeletion

import di.swallet.wpb.presentation.domain.RpRegistryRecord
import org.springframework.stereotype.Component

/**
 * Builds TS10 interactingPartyContact arrays: country + email/phone/infoURI values (DASH_03g).
 */
@Component
class Ts10InteractingPartyContactBuilder(
    private val classifier: SupportUriClassifier,
) {
    fun fromRegistry(registry: RpRegistryRecord, country: String? = null): List<String> {
        val resolvedCountry = country?.takeIf { it.isNotBlank() }
            ?: registry.supervisoryAuthority?.country?.takeIf { it.length == 2 }
        return buildContactArray(resolvedCountry, registry.supportUris)
    }

    fun fromSupportUris(country: String?, supportUris: List<String>): List<String> =
        buildContactArray(country?.takeIf { it.isNotBlank() }, supportUris)

    fun parseStoredContact(contact: List<String>): ParsedDeletionContacts {
        val (country, values) = splitCountryPrefix(contact)
        val classified = classifier.classifyAll(values)
        return ParsedDeletionContacts(country = country, contacts = classified)
    }

    private fun buildContactArray(country: String?, supportUris: List<String>): List<String> {
        val classified = classifier.classifyAll(supportUris)
        if (classified.isEmpty() && country.isNullOrBlank()) return emptyList()

        val result = mutableListOf<String>()
        country?.let { result.add(it) }
        classified.filter { it.channel == DeletionContactChannel.EMAIL }.forEach { result.add(it.value) }
        classified.filter { it.channel == DeletionContactChannel.PHONE }.forEach { result.add(it.value) }
        classified.filter { it.channel == DeletionContactChannel.WEB }.forEach { result.add(it.value) }
        return result
    }

    private fun splitCountryPrefix(contact: List<String>): Pair<String?, List<String>> {
        if (contact.isEmpty()) return null to emptyList()
        val first = contact.first()
        return if (COUNTRY_REGEX.matches(first)) {
            first to contact.drop(1)
        } else {
            null to contact
        }
    }

    companion object {
        private val COUNTRY_REGEX = Regex("^[A-Z]{2}$")
    }
}

data class ParsedDeletionContacts(
    val country: String? = null,
    val contacts: List<ClassifiedDeletionContact> = emptyList(),
) {
    fun hasDeletionChannel(): Boolean = contacts.isNotEmpty()
}
