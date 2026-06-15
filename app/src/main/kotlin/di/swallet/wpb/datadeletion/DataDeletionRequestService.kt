/**
 * Orchestrates GDPR deletion eligibility, contact resolution, and transaction logging.
 */

package di.swallet.wpb.datadeletion

import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.service.TransactionLogService
import di.swallet.wpb.transactionlog.service.TransactionLogger
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Summary of a completed presentation eligible for a data deletion request. */
data class EligiblePresentation(
    val presentationTransactionId: String,
    val rpIdentifier: String?,
    val rpName: String?,
    val presentationTime: String,
    val presentedClaims: List<Ts10ClaimInfo>,
    val hasStoredDeletionContacts: Boolean,
)

/** Request to initiate a deletion request against a stored presentation. */
data class DataDeletionInitiateRequest(
    val holderId: String,
    val presentationTransactionId: String,
    val claimsToDelete: List<Ts10ClaimInfo>? = null,
    val deleteAllPresented: Boolean = false,
    val consentRegistryLookup: Boolean = false,
)

/** One actionable deletion contact channel returned to the wallet UI. */
data class DataDeletionActionResponse(
    val channel: String,
    val uri: String,
)

/** Result of initiating a deletion request with contact actions and user notices. */
data class DataDeletionInitiateResponse(
    val transactionId: String,
    val sourcePresentationTransactionId: String,
    val rpIdentifier: String?,
    val rpName: String?,
    val availableActions: List<DataDeletionActionResponse>,
    val registryLookupPerformed: Boolean,
    val userNotice: String?,
)

/** Resolves deletion contacts, validates claim selection, and logs deletion transactions. */
@Service
class DataDeletionRequestService(
    private val transactionLogService: TransactionLogService,
    private val contactResolver: DeletionContactResolver,
    private val actionBuilder: DeletionActionBuilder,
    private val mapper: DataDeletionRequestMapper,
    private val transactionLogger: TransactionLogger,
) {
    /** Lists completed presentations with presented claims and an interacting party reference. */
    fun listEligible(holderId: String): List<EligiblePresentation> =
        transactionLogService.list(holderId)
            .asSequence()
            .filter { it.transactionType == Ts10TransactionType.Presentation.name }
            .filter { it.transactionResult == Ts10TransactionResult.Completed.name }
            .mapNotNull { summary ->
                val transaction = runCatching {
                    transactionLogService.get(holderId, summary.transactionId)
                }.getOrNull() ?: return@mapNotNull null
                val presentation = transaction.presentation ?: return@mapNotNull null
                if (!isEligiblePresentation(presentation)) return@mapNotNull null
                EligiblePresentation(
                    presentationTransactionId = summary.transactionId,
                    rpIdentifier = presentation.interactingPartyIdentifier?.identifier,
                    rpName = presentation.interactingPartyName,
                    presentationTime = transaction.time,
                    presentedClaims = presentation.listOfClaimsPresented,
                    hasStoredDeletionContacts = contactResolver.hasStoredDeletionContacts(presentation),
                )
            }
            .toList()

    /** Validates claim selection, builds contact actions, and records a deletion request transaction. */
    fun initiate(request: DataDeletionInitiateRequest): DataDeletionInitiateResponse {
        if (request.holderId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "holderId is required")
        }
        val context = contactResolver.resolve(
            holderId = request.holderId,
            presentationTransactionId = request.presentationTransactionId,
            consentRegistryLookup = request.consentRegistryLookup,
        )
        val claimsToLog = resolveClaimsToDelete(
            deleteAllPresented = request.deleteAllPresented,
            claimsToDelete = request.claimsToDelete,
            presentedClaims = context.presentedClaims,
        )
        val actions = actionBuilder.buildAll(
            contacts = context.contacts,
            rpName = context.rpName ?: "Service Provider",
            presentationTime = context.presentationTime,
            claims = claimsToLog,
            deleteAllPresented = request.deleteAllPresented,
        )
        val transaction = mapper.toTransaction(
            rpIdentifier = context.rpIdentifier,
            rpName = context.rpName,
            claims = claimsToLog,
        )
        transactionLogger.logDataDeletionRequest(request.holderId, transaction)

        return DataDeletionInitiateResponse(
            transactionId = transaction.transactionIdentifier,
            sourcePresentationTransactionId = context.presentationTransactionId,
            rpIdentifier = context.rpIdentifier,
            rpName = context.rpName,
            availableActions = actions.map {
                DataDeletionActionResponse(channel = it.channel.name, uri = it.uri)
            },
            registryLookupPerformed = context.registryLookupPerformed,
            userNotice = context.userNotice,
        )
    }

    /** Returns true when the presentation has claims and an interacting party reference. */
    private fun isEligiblePresentation(presentation: Ts10Presentation): Boolean {
        if (presentation.listOfClaimsPresented.isEmpty()) return false
        return !presentation.interactingPartyIdentifier?.identifier.isNullOrBlank() ||
            !presentation.registrarURL.isNullOrBlank()
    }

    /** Validates requested claims against what was actually presented. */
    private fun resolveClaimsToDelete(
        deleteAllPresented: Boolean,
        claimsToDelete: List<Ts10ClaimInfo>?,
        presentedClaims: List<Ts10ClaimInfo>,
    ): List<Ts10ClaimInfo> {
        if (deleteAllPresented) return presentedClaims
        if (claimsToDelete.isNullOrEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Either deleteAllPresented=true or a non-empty claimsToDelete list is required",
            )
        }
        val presentedByCredential = presentedClaims.associate { it.credentialIdentifier to it.claims.toSet() }
        claimsToDelete.forEach { requested ->
            val presented = presentedByCredential[requested.credentialIdentifier]
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Credential '${requested.credentialIdentifier}' was not presented",
                )
            requested.claims.forEach { claim ->
                if (claim !in presented) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Claim '$claim' was not presented for credential '${requested.credentialIdentifier}'",
                    )
                }
            }
        }
        return claimsToDelete
    }
}
