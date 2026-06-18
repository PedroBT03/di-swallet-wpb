/**
 * Orchestrates DPA report eligibility checks, contact resolution, and transaction logging.
 */

package di.swallet.wpb.dpareport

import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10PresentationPartyRef
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.service.TransactionLogService
import di.swallet.wpb.transactionlog.service.TransactionLogSummary
import di.swallet.wpb.transactionlog.service.TransactionLogger
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Summary of a presentation that can be reported to a DPA. */
data class EligibleDpaReportPresentation(
    val presentationTransactionId: String,
    val transactionResult: String,
    val rpIdentifier: String?,
    val rpName: String?,
    val presentationTime: String,
    val hasStoredDpaContacts: Boolean,
)

/** Request to start a DPA report from a stored presentation transaction. */
data class DpaReportInitiateRequest(
    val holderId: String,
    val presentationTransactionId: String,
    val consentRegistryLookup: Boolean = true,
)

/** One actionable DPA contact channel returned to the wallet UI. */
data class DpaReportActionResponse(
    val channel: String,
    val uri: String,
)

/** Result of initiating a DPA report, including substantiation and available contact actions. */
data class DpaReportInitiateResponse(
    val transactionId: String?,
    val sourcePresentationTransactionId: String,
    val rpIdentifier: String?,
    val rpName: String?,
    val dpaName: String?,
    val dpaCountry: String?,
    val dnsName: String?,
    val dnsNameSource: String?,
    val availableActions: List<DpaReportActionResponse>,
    val registryLookupPerformed: Boolean,
    val userNotice: String?,
    val substantiationDocument: Ts10Transaction,
)

/** Resolves DPA contacts, builds report actions, and records DPA report transactions. */
@Service
class DpaReportService(
    private val transactionLogService: TransactionLogService,
    private val contactResolver: DpaContactResolver,
    private val actionBuilder: DpaActionBuilder,
    private val mapper: DpaReportMapper,
    private val transactionLogger: TransactionLogger,
) {
    /** Lists completed presentations that reference an interacting party and may have DPA contacts. */
    fun listEligible(holderId: String): List<EligibleDpaReportPresentation> =
        transactionLogService.list(holderId)
            .asSequence()
            .filter { it.transactionType == Ts10TransactionType.Presentation.name }
            .mapNotNull { summary -> toEligible(holderId, summary) }
            .toList()

    /** Resolves DPA contacts for a presentation, logs a report transaction, and returns actionable URIs. */
    fun initiate(request: DpaReportInitiateRequest): DpaReportInitiateResponse {
        if (request.holderId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "holderId is required")
        }
        val context = contactResolver.resolve(
            holderId = request.holderId,
            presentationTransactionId = request.presentationTransactionId,
            consentRegistryLookup = request.consentRegistryLookup,
        )
        val substantiation = transactionLogService.get(request.holderId, context.presentationTransactionId)
        val dnsName = context.rpDnsName?.value ?: "unknown relying party"
        val actions = actionBuilder.buildAll(context.selectedDpa.contacts, dnsName)

        val transactionId = if (actions.isNotEmpty()) {
            val primary = actions.first()
            val transaction = mapper.toTransaction(
                dpaName = context.selectedDpa.name,
                dpaCountry = context.selectedDpa.country,
                reportChannel = primary.channel,
                reportContact = primary.contactValue,
            )
            transactionLogger.logDpaReport(request.holderId, transaction)
            transaction.transactionIdentifier
        } else {
            null
        }

        return DpaReportInitiateResponse(
            transactionId = transactionId,
            sourcePresentationTransactionId = context.presentationTransactionId,
            rpIdentifier = context.rpIdentifier,
            rpName = context.rpName,
            dpaName = context.selectedDpa.name,
            dpaCountry = context.selectedDpa.country,
            dnsName = context.rpDnsName?.value,
            dnsNameSource = context.rpDnsName?.source?.name,
            availableActions = actions.map {
                DpaReportActionResponse(channel = it.channel.name, uri = it.uri)
            },
            registryLookupPerformed = context.registryLookupPerformed,
            userNotice = context.userNotice,
            substantiationDocument = substantiation,
        )
    }

    /** Maps a presentation summary to an eligible DPA report entry when party reference data exists. */
    private fun toEligible(holderId: String, summary: TransactionLogSummary): EligibleDpaReportPresentation? {
        val transaction = runCatching {
            transactionLogService.get(holderId, summary.transactionId)
        }.getOrNull() ?: return null
        val presentation = transaction.presentation ?: return null
        if (!hasPartyRef(presentation)) return null
        return EligibleDpaReportPresentation(
            presentationTransactionId = summary.transactionId,
            transactionResult = summary.transactionResult,
            rpIdentifier = presentation.interactingPartyIdentifier?.identifier,
            rpName = presentation.interactingPartyName,
            presentationTime = transaction.time,
            hasStoredDpaContacts = contactResolver.hasStoredDpaContacts(presentation),
        )
    }

    /** Returns true when the presentation stores an RP identifier or registrar URL. */
    private fun hasPartyRef(presentation: di.swallet.wpb.transactionlog.domain.Ts10Presentation): Boolean =
        Ts10PresentationPartyRef.hasReference(presentation)
}
