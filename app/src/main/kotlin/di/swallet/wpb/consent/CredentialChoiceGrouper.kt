/**
 * Groups presentation credential candidates by query for consent UI selection.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.domain.CredentialTypeLabels
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import org.springframework.stereotype.Component

/**
 * Organizes matching credentials into per-query choice groups for the consent screen.
 */
@Component
class CredentialChoiceGrouper {

    /**
     * Groups candidates by query id and marks groups that need explicit holder selection.
     */
    fun group(candidates: List<CredentialCandidate>): List<CredentialChoiceGroup> {
        val byQuery = candidates.groupBy { it.queryId }
        return byQuery.map { (queryId, queryCandidates) ->
            val first = queryCandidates.first()
            val requiresUserSelection = requiresExplicitSelection(queryCandidates)
            CredentialChoiceGroup(
                queryId = queryId,
                credentialType = CredentialTypeLabels.displayLabel(first.credentialType),
                format = first.format,
                candidates = queryCandidates.map { candidate ->
                    CredentialChoiceOption(
                        candidateId = candidate.candidateId,
                        credentialId = candidate.credentialId,
                        label = buildLabel(candidate, showCredentialId = queryCandidates.size > 1),
                        deviceBound = false,
                    )
                },
                requiresUserSelection = requiresUserSelection,
            )
        }
    }

    /**
     * Returns true when multiple same-type credentials exist and the holder must pick one.
     */
    fun requiresExplicitSelection(candidates: List<CredentialCandidate>): Boolean {
        if (candidates.size <= 1) return false
        val first = candidates.first()
        return candidates.all {
            it.credentialType == first.credentialType && it.format == first.format
        }
    }

    /**
     * Builds a holder-facing label; omits internal ids when only one credential is available.
     */
    private fun buildLabel(candidate: CredentialCandidate, showCredentialId: Boolean): String {
        val typeLabel = CredentialTypeLabels.displayLabel(candidate.credentialType)
        if (!showCredentialId) {
            return typeLabel
        }
        val idSuffix = candidate.credentialId?.let { " #$it" }.orEmpty()
        return "$typeLabel$idSuffix"
    }
}
