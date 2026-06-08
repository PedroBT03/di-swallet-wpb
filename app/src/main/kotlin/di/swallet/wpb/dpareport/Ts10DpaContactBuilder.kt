package di.swallet.wpb.dpareport

import di.swallet.wpb.datadeletion.ClassifiedDeletionContact
import di.swallet.wpb.datadeletion.DeletionContactChannel
import di.swallet.wpb.datadeletion.SupportUriClassifier
import di.swallet.wpb.presentation.domain.SupervisoryAuthorityContact
import org.springframework.stereotype.Component

/**
 * Builds TS10 dpaContact arrays: email, phone, infoURI (web form) — country lives in dpaCountry.
 */
@Component
class Ts10DpaContactBuilder(
    private val classifier: SupportUriClassifier,
) {
    fun fromSupervisoryAuthority(dpa: SupervisoryAuthorityContact): List<String> =
        buildContactArray(dpa.email + dpa.phone + dpa.formUri)

    fun parseStoredContact(dpaContact: List<String>): ParsedDpaContacts {
        val classified = classifier.classifyAll(dpaContact)
        return ParsedDpaContacts(contacts = classified)
    }

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

data class ParsedDpaContacts(
    val contacts: List<ClassifiedDeletionContact> = emptyList(),
) {
    fun hasReportChannel(): Boolean = contacts.isNotEmpty()
}
