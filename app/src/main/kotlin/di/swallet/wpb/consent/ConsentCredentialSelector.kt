package di.swallet.wpb.consent

import di.swallet.wpb.config.ConsentProperties
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.SelectedCredential
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class ConsentCredentialSelector(
    private val consentProperties: ConsentProperties,
    private val choiceGrouper: CredentialChoiceGrouper,
) {

    fun select(
        context: PresentationContext,
        selectedCredentialIds: List<String>,
    ): List<SelectedCredential> {
        val requiredQueryIds = context.presentationRequirements?.credentialQueries
            ?.map { it.id }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: context.presentationRequirements?.credentialQueryIds?.toSet()
            ?: context.credentialCandidates.map { it.queryId }.toSet()

        if (consentProperties.enabled && consentProperties.requireExplicitCredentialChoice) {
            if (selectedCredentialIds.isEmpty()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "selectedCredentialIds is required when explicit credential choice is enabled",
                )
            }
        }

        val candidatesById = context.credentialCandidates.associateBy { it.candidateId }
        val selectedCandidates = if (selectedCredentialIds.isEmpty() && !consentProperties.enabled) {
            context.credentialCandidates.groupBy { it.queryId }.map { it.value.first() }
        } else {
            selectedCredentialIds.mapNotNull { candidatesById[it] }
        }

        if (selectedCandidates.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No valid credentials were selected",
            )
        }

        validateChoiceGroups(context.credentialCandidates, selectedCandidates)

        if (consentProperties.enabled && consentProperties.enforceAllOrNothing) {
            val selectedQueryIds = selectedCandidates.map { it.queryId }.toSet()
            if (selectedQueryIds != requiredQueryIds) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "All presentation queries must be satisfied (expected=$requiredQueryIds, selected=$selectedQueryIds)",
                )
            }
        }

        return selectedCandidates.map { candidate ->
            SelectedCredential(
                candidateId = candidate.candidateId,
                credentialId = candidate.credentialId,
                holderId = candidate.holderId,
                queryId = candidate.queryId,
                credentialType = candidate.credentialType,
                format = candidate.format,
                requestedClaimPaths = candidate.requestedClaimPaths,
            )
        }
    }

    private fun validateChoiceGroups(
        allCandidates: List<CredentialCandidate>,
        selected: List<CredentialCandidate>,
    ) {
        val groups = choiceGrouper.group(allCandidates)
        groups.filter { it.requiresUserSelection }.forEach { group ->
            val selectedInGroup = selected.count { it.queryId == group.queryId }
            if (selectedInGroup != 1) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Exactly one credential must be selected for query ${group.queryId}",
                )
            }
        }
    }
}
