/**
 * Builds and parses TS10 dpaContact arrays from supervisory authority contact data.
 */

package di.swallet.wpb.dpareport

import di.swallet.wpb.datadeletion.ClassifiedDeletionContact
import di.swallet.wpb.datadeletion.DeletionContactChannel
import di.swallet.wpb.datadeletion.SupportUriClassifier
import di.swallet.wpb.presentation.domain.SupervisoryAuthorityContact
import org.springframework.stereotype.Component

/** Builds TS10 dpaContact arrays with email, phone, and web form URIs. */
@Component
class Ts10DpaContactBuilder(
    private val classifier: SupportUriClassifier,
) {
    /** Serializes a supervisory authority into a TS10 dpaContact string array. */
    fun fromSupervisoryAuthority(dpa: SupervisoryAuthorityContact): List<String> =
        buildContactArray(dpa.email + dpa.phone + dpa.formUri)

    /** Classifies stored dpaContact values into typed report channels. */
    fun parseStoredContact(dpaContact: List<String>): ParsedDpaContacts {
        val classified = classifier.classifyAll(dpaContact)
        return ParsedDpaContacts(contacts = classified)
    }

    /** Orders classified contacts as email, phone, then web values for TS10 storage. */
    private fun buildContactArray(rawValues: List<String>): List<String> {
        val classified = classifier.classifyAll(rawValues)
        if (classified.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        classified.filter { it.channel == DeletionContactChannel.EMAIL }.forEach { result.add(it.value) }
        classified.filter { it.channel == DeletionContactChannel.PHONE }.forEach { result.add(it.value) }
        classified.filter { it.channel == DeletionContactChannel.WEB }.forEach { result.add(it.value) }
        return result
    }
}

/** Parsed DPA contacts ready for action URI generation. */
data class ParsedDpaContacts(
    val contacts: List<ClassifiedDeletionContact> = emptyList(),
) {
    /** Returns true when at least one report channel is available. */
    fun hasReportChannel(): Boolean = contacts.isNotEmpty()
}
