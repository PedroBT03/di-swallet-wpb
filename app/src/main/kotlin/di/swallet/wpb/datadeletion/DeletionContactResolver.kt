/**
 * Resolves relying-party deletion contacts from presentation logs or the RP registry.
 */

package di.swallet.wpb.datadeletion

import di.swallet.wpb.config.DataDeletionRequestProperties
import di.swallet.wpb.presentation.registry.RegistryResolution
import di.swallet.wpb.presentation.registry.RpRegistryResolver
import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.service.TransactionLogService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** Fully resolved context for initiating a data deletion request. */
data class ResolvedDeletionContext(
    val presentationTransactionId: String,
    val presentationTime: String,
    val rpIdentifier: String?,
    val rpName: String?,
    val presentedClaims: List<Ts10ClaimInfo>,
    val contacts: ParsedDeletionContacts,
    val registryLookupPerformed: Boolean,
    val userNotice: String?,
)

/** Resolves deletion contacts and validates presentation eligibility for erasure requests. */
@Component
class DeletionContactResolver(
    private val transactionLogService: TransactionLogService,
    private val contactBuilder: Ts10InteractingPartyContactBuilder,
    private val registryResolver: RpRegistryResolver,
    private val properties: DataDeletionRequestProperties,
) {
    /** Loads the presentation, resolves contacts, and fails when no deletion channel exists. */
    fun resolve(
        holderId: String,
        presentationTransactionId: String,
        consentRegistryLookup: Boolean,
    ): ResolvedDeletionContext {
        val transaction = transactionLogService.get(holderId, presentationTransactionId)
        val presentation = validateEligiblePresentation(transaction, presentationTransactionId)

        var contacts = contactBuilder.parseStoredContact(presentation.interactingPartyContact)
        var registryLookupPerformed = false
        var userNotice: String? = null

        if (!contacts.hasDeletionChannel()) {
            val rpIdentifier = presentation.interactingPartyIdentifier?.identifier
            if (consentRegistryLookup && !rpIdentifier.isNullOrBlank()) {
                contacts = lookupRegistryContacts(rpIdentifier)
                registryLookupPerformed = contacts.hasDeletionChannel()
                if (registryLookupPerformed) {
                    userNotice = properties.registryLookupNotice
                }
            }
        }

        if (!contacts.hasDeletionChannel()) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "No deletion contact channel available for presentation $presentationTransactionId",
            )
        }

        return ResolvedDeletionContext(
            presentationTransactionId = presentationTransactionId,
            presentationTime = transaction.time,
            rpIdentifier = presentation.interactingPartyIdentifier?.identifier,
            rpName = presentation.interactingPartyName,
            presentedClaims = presentation.listOfClaimsPresented,
            contacts = contacts,
            registryLookupPerformed = registryLookupPerformed,
            userNotice = userNotice,
        )
    }

    /** Returns true when the presentation stores usable deletion contact information. */
    fun hasStoredDeletionContacts(presentation: Ts10Presentation): Boolean =
        contactBuilder.parseStoredContact(presentation.interactingPartyContact).hasDeletionChannel()

    /** Ensures the transaction is a completed presentation with claims and an RP reference. */
    private fun validateEligiblePresentation(transaction: Ts10Transaction, presentationTransactionId: String): Ts10Presentation {
        if (transaction.transactionType != Ts10TransactionType.Presentation.name) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Transaction $presentationTransactionId is not a presentation",
            )
        }
        if (transaction.transactionResult != Ts10TransactionResult.Completed.name) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Presentation $presentationTransactionId is not completed",
            )
        }
        val presentation = transaction.presentation
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Presentation payload missing")
        if (presentation.listOfClaimsPresented.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Presentation $presentationTransactionId has no presented claims",
            )
        }
        val hasPartyRef = !presentation.interactingPartyIdentifier?.identifier.isNullOrBlank() ||
            !presentation.registrarURL.isNullOrBlank()
        if (!hasPartyRef) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Presentation $presentationTransactionId has no interacting party reference",
            )
        }
        return presentation
    }

    /** Looks up support URIs for an RP identifier and classifies them as deletion channels. */
    private fun lookupRegistryContacts(rpIdentifier: String): ParsedDeletionContacts {
        return when (val resolution = registryResolver.lookupByIdentifier(rpIdentifier)) {
            is RegistryResolution.Accepted -> {
                val classified = contactBuilder.fromRegistry(resolution.record)
                contactBuilder.parseStoredContact(classified)
            }
            is RegistryResolution.Rejected -> {
                throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Registry lookup failed for '$rpIdentifier': ${resolution.reason}",
                )
            }
        }
    }
}
