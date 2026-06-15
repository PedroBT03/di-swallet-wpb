/**
 * Builds and parses TS10 interactingPartyContact arrays for GDPR deletion workflows.
 */

package di.swallet.wpb.datadeletion

import di.swallet.wpb.presentation.domain.RpRegistryRecord
import org.springframework.stereotype.Component

/** Builds TS10 interactingPartyContact arrays with country prefix and support URIs. */
@Component
class Ts10InteractingPartyContactBuilder(
    private val classifier: SupportUriClassifier,
) {
    /** Builds a contact array from an RP registry record and optional country override. */
    fun fromRegistry(registry: RpRegistryRecord, country: String? = null): List<String> {
        val resolvedCountry = country?.takeIf { it.isNotBlank() }
            ?: registry.supervisoryAuthority?.country?.takeIf { it.length == 2 }
        return buildContactArray(resolvedCountry, registry.supportUris)
    }

    /** Builds a contact array from explicit country and support URI values. */
    fun fromSupportUris(country: String?, supportUris: List<String>): List<String> =
        buildContactArray(country?.takeIf { it.isNotBlank() }, supportUris)

    /** Parses a stored interactingPartyContact array into country and classified channels. */
    fun parseStoredContact(contact: List<String>): ParsedDeletionContacts {
        val (country, values) = splitCountryPrefix(contact)
        val classified = classifier.classifyAll(values)
        return ParsedDeletionContacts(country = country, contacts = classified)
    }

    /** Orders classified contacts as country, email, phone, then web values for TS10 storage. */
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

    /** Splits an optional two-letter country prefix from the remaining contact values. */
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

/** Parsed deletion contacts with optional country prefix. */
data class ParsedDeletionContacts(
    val country: String? = null,
    val contacts: List<ClassifiedDeletionContact> = emptyList(),
) {
    /** Returns true when at least one deletion channel is available. */
    fun hasDeletionChannel(): Boolean = contacts.isNotEmpty()
}
