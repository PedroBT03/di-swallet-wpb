/**
 * Resolves DPA contact candidates from presentation logs, RP registry, or provider fallback.
 */

package di.swallet.wpb.dpareport

import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.ProviderFallbackDpa
import di.swallet.wpb.presentation.domain.SupervisoryAuthorityContact
import di.swallet.wpb.presentation.registry.RegistryResolution
import di.swallet.wpb.presentation.registry.RpRegistryResolver
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10PresentationPartyRef
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.service.TransactionLogService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** Source from which a DPA contact candidate was obtained. */
enum class DpaContactSource {
    LOG,
    REGISTRY,
    PROVIDER_FALLBACK,
}

/** One DPA candidate with name, country, contacts, and provenance. */
data class DpaContactCandidate(
    val name: String?,
    val country: String?,
    val contacts: ParsedDpaContacts,
    val source: DpaContactSource,
)

/** Fully resolved context for initiating a DPA report from a presentation transaction. */
data class ResolvedDpaReportContext(
    val presentationTransactionId: String,
    val presentationTime: String,
    val presentation: Ts10Presentation,
    val rpIdentifier: String?,
    val rpName: String?,
    val rpDnsName: ResolvedRpDnsName?,
    val dpaCandidates: List<DpaContactCandidate>,
    val selectedDpa: DpaContactCandidate,
    val registryLookupPerformed: Boolean,
    val userNotice: String?,
)

/** Resolves DPA contacts and RP metadata needed to initiate a holder DPA report. */
@Component
class DpaContactResolver(
    private val transactionLogService: TransactionLogService,
    private val dpaContactBuilder: Ts10DpaContactBuilder,
    private val registryResolver: RpRegistryResolver,
    private val rpDnsNameResolver: RpDnsNameResolver,
    private val properties: DpaReportProperties,
) {
    /** Loads the presentation, gathers DPA candidates, and selects the best available contact set. */
    fun resolve(
        holderId: String,
        presentationTransactionId: String,
        consentRegistryLookup: Boolean,
    ): ResolvedDpaReportContext {
        val transaction = transactionLogService.get(holderId, presentationTransactionId)
        val presentation = validateReportablePresentation(transaction, presentationTransactionId)

        val candidates = mutableListOf<DpaContactCandidate>()
        var registryLookupPerformed = false
        var userNotice: String? = null

        val logContacts = dpaContactBuilder.parseStoredContact(presentation.dpaContact)
        if (logContacts.hasReportChannel() || !presentation.dpaName.isNullOrBlank()) {
            candidates.add(
                DpaContactCandidate(
                    name = presentation.dpaName,
                    country = presentation.dpaCountry,
                    contacts = logContacts,
                    source = DpaContactSource.LOG,
                ),
            )
        }

        if (candidates.none { it.contacts.hasReportChannel() }) {
            val rpIdentifier = presentation.interactingPartyIdentifier?.identifier
            if (consentRegistryLookup && !rpIdentifier.isNullOrBlank()) {
                lookupRegistryDpa(rpIdentifier)?.let { registryCandidate ->
                    candidates.add(registryCandidate)
                    registryLookupPerformed = true
                    if (registryCandidate.contacts.hasReportChannel()) {
                        userNotice = properties.registryLookupNotice
                    }
                }
            }
        }

        if (candidates.none { it.contacts.hasReportChannel() }) {
            buildProviderFallbackCandidate()?.let { candidates.add(it) }
        }

        val selected = candidates.firstOrNull { it.contacts.hasReportChannel() }
            ?: candidates.firstOrNull()
            ?: DpaContactCandidate(
                name = null,
                country = null,
                contacts = ParsedDpaContacts(),
                source = DpaContactSource.LOG,
            )

        if (!selected.contacts.hasReportChannel()) {
            userNotice = properties.noContactNotice
        }

        return ResolvedDpaReportContext(
            presentationTransactionId = presentationTransactionId,
            presentationTime = transaction.time,
            presentation = presentation,
            rpIdentifier = presentation.interactingPartyIdentifier?.identifier,
            rpName = presentation.interactingPartyName,
            rpDnsName = rpDnsNameResolver.fromPresentation(presentation),
            dpaCandidates = candidates,
            selectedDpa = selected,
            registryLookupPerformed = registryLookupPerformed,
            userNotice = userNotice,
        )
    }

    /** Returns true when the presentation already stores usable DPA contact information. */
    fun hasStoredDpaContacts(presentation: Ts10Presentation): Boolean =
        dpaContactBuilder.parseStoredContact(presentation.dpaContact).hasReportChannel() ||
            !presentation.dpaName.isNullOrBlank()

    /** Ensures the transaction is a reportable presentation with an interacting party reference. */
    private fun validateReportablePresentation(
        transaction: Ts10Transaction,
        presentationTransactionId: String,
    ): Ts10Presentation {
        if (transaction.transactionType != Ts10TransactionType.Presentation.name) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Transaction $presentationTransactionId is not a presentation",
            )
        }
        val presentation = transaction.presentation
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Presentation payload missing")
        val hasPartyRef = Ts10PresentationPartyRef.hasReference(presentation)
        if (!hasPartyRef) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Presentation $presentationTransactionId has no interacting party reference",
            )
        }
        return presentation
    }

    /** Looks up supervisory authority contacts for an RP identifier in the registry. */
    private fun lookupRegistryDpa(rpIdentifier: String): DpaContactCandidate? =
        when (val resolution = registryResolver.lookupByIdentifier(rpIdentifier)) {
            is RegistryResolution.Accepted -> {
                val dpa = resolution.record.supervisoryAuthority ?: return null
                DpaContactCandidate(
                    name = dpa.name,
                    country = dpa.country,
                    contacts = dpaContactBuilder.parseStoredContact(
                        dpaContactBuilder.fromSupervisoryAuthority(dpa),
                    ),
                    source = DpaContactSource.REGISTRY,
                )
            }
            is RegistryResolution.Rejected -> null
        }

    /** Builds a configured provider fallback DPA candidate when registry and log data are missing. */
    private fun buildProviderFallbackCandidate(): DpaContactCandidate? {
        val fallback = properties.providerFallbackDpa
        if (!fallback.isConfigured()) return null
        return DpaContactCandidate(
            name = fallback.name.takeIf { it.isNotBlank() },
            country = fallback.country.takeIf { it.isNotBlank() },
            contacts = dpaContactBuilder.parseStoredContact(
                dpaContactBuilder.fromSupervisoryAuthority(fallback.toSupervisoryAuthority()),
            ),
            source = DpaContactSource.PROVIDER_FALLBACK,
        )
    }

    /** Converts configured fallback properties into a supervisory authority contact shape. */
    private fun ProviderFallbackDpa.toSupervisoryAuthority(): SupervisoryAuthorityContact =
        SupervisoryAuthorityContact(
            name = name.takeIf { it.isNotBlank() },
            country = country.takeIf { it.isNotBlank() },
            email = listOfNotNull(email.takeIf { it.isNotBlank() }),
            phone = listOfNotNull(phone.takeIf { it.isNotBlank() }),
            formUri = listOfNotNull(formUri.takeIf { it.isNotBlank() }),
        )
}
