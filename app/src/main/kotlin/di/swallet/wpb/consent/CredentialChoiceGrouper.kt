package di.swallet.wpb.consent

import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import org.springframework.stereotype.Component

@Component
class CredentialChoiceGrouper {

    fun group(candidates: List<CredentialCandidate>): List<CredentialChoiceGroup> {
        val byQuery = candidates.groupBy { it.queryId }
        return byQuery.map { (queryId, queryCandidates) ->
            val first = queryCandidates.first()
            val requiresUserSelection = requiresExplicitSelection(queryCandidates)
            CredentialChoiceGroup(
                queryId = queryId,
                credentialType = first.credentialType,
                format = first.format,
                candidates = queryCandidates.map { candidate ->
                    CredentialChoiceOption(
                        candidateId = candidate.candidateId,
                        credentialId = candidate.credentialId,
                        label = buildLabel(candidate),
                        deviceBound = false,
                    )
                },
                requiresUserSelection = requiresUserSelection,
            )
        }
    }

    fun requiresExplicitSelection(candidates: List<CredentialCandidate>): Boolean {
        if (candidates.size <= 1) return false
        val first = candidates.first()
        return candidates.all {
            it.credentialType == first.credentialType && it.format == first.format
        }
    }

    private fun buildLabel(candidate: CredentialCandidate): String {
        val formatLabel = when (candidate.format) {
            CredentialFormat.SD_JWT -> "SD-JWT"
            CredentialFormat.MDOC -> "mDoc"
        }
        val idSuffix = candidate.credentialId?.let { " #$it" }.orEmpty()
        return "${candidate.credentialType} ($formatLabel)$idSuffix"
    }
}
