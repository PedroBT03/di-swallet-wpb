package di.swallet.wpb.consent

import di.swallet.wpb.config.ConsentProperties
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationState
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class PresentationConsentViewBuilder(
    private val consentProperties: ConsentProperties,
    private val choiceGrouper: CredentialChoiceGrouper,
    private val minimizationEvaluator: AttributeMinimizationEvaluator,
    private val walletCredentialRepository: WalletCredentialRepository,
) {

    fun build(context: PresentationContext): PresentationConsentView {
        if (!consentProperties.enabled) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Presentation consent UX is disabled")
        }
        if (context.state != PresentationState.CONSENT_PENDING) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Session is not awaiting consent (state=${context.state})",
            )
        }

        val registry = context.registryRecord
        val intendedUse = registry?.intendedUses?.firstOrNull()
        val registryWarnings = buildRegistryWarnings(context)
        val choiceGroups = enrichDeviceBound(choiceGrouper.group(context.credentialCandidates))

        return PresentationConsentView(
            sessionId = context.sessionMeta.sessionId,
            state = context.state,
            holderId = context.sessionMeta.holderId,
            verifier = VerifierConsentInfo(
                clientId = context.verifierIdentity?.clientId
                    ?: context.authorizationRequest?.clientId
                    ?: "unknown",
                displayName = context.verifierIdentity?.displayName
                    ?: context.authorizationRequest?.verifierDisplayName
                    ?: registry?.tradeName,
                trusted = context.trustDecision?.trusted == true,
                trustReason = context.trustDecision?.reason,
            ),
            intendedUse = intendedUse?.purpose.orEmpty(),
            privacyPolicyUri = intendedUse?.privacyPolicyUris?.firstOrNull(),
            registryWarnings = registryWarnings,
            minimization = minimizationEvaluator.evaluate(context),
            queries = buildQueries(context),
            choiceGroups = choiceGroups,
            approvalMode = if (consentProperties.enforceAllOrNothing) {
                ApprovalMode.ALL_OR_NOTHING
            } else {
                ApprovalMode.ALL_OR_NOTHING
            },
        )
    }

    private fun buildQueries(context: PresentationContext): List<QueryConsentItem> {
        val queries = context.presentationRequirements?.credentialQueries.orEmpty()
        return queries.map { query ->
            QueryConsentItem(
                queryId = query.id,
                format = query.format,
                credentialTypeHints = query.credentialTypeHints,
                requestedClaims = query.requestedClaimPaths.map { path ->
                    ClaimConsentItem(path = path.toDotNotation(), label = path.toDotNotation())
                }.ifEmpty {
                    query.requestedClaims.map { ClaimConsentItem(path = it, label = it) }
                },
            )
        }
    }

    private fun buildRegistryWarnings(context: PresentationContext): List<ConsentWarning> {
        val warnings = mutableListOf<ConsentWarning>()
        val decision = context.registryDecision
        if (decision?.accepted == true && decision.rpIdentifier.isNullOrBlank()) {
            warnings += ConsentWarning("registry_identifier_missing", "Registry identifier is unavailable")
        }
        if (decision?.accepted == false) {
            warnings += ConsentWarning(
                code = "registry_rejected",
                message = decision.reason ?: "Registry validation failed",
            )
        }
        return warnings
    }

    private fun enrichDeviceBound(groups: List<CredentialChoiceGroup>): List<CredentialChoiceGroup> =
        groups.map { group ->
            group.copy(
                candidates = group.candidates.map { option ->
                    val deviceBound = option.credentialId?.let { id ->
                        walletCredentialRepository.findById(id).map { it.deviceBound }.orElse(false)
                    } ?: false
                    option.copy(deviceBound = deviceBound)
                },
            )
        }
}
