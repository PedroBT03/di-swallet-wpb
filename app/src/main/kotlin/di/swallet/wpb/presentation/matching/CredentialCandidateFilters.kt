/**
 * Filters for presentation credential candidates.
 */

package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.CredentialTypeLabels
import di.swallet.wpb.presentation.domain.CredentialCandidate

/** Keeps only candidates backed by a stored wallet credential (excludes demo-mode synthetics). */
fun List<CredentialCandidate>.walletBackedOnly(): List<CredentialCandidate> =
    filter { it.credentialId != null }

/** Keeps the newest stored credential per query and document family (PID / mDL). */
fun List<CredentialCandidate>.dedupeLatestPerDocumentFamily(): List<CredentialCandidate> =
    groupBy { it.queryId }
        .flatMap { (_, queryCandidates) ->
            queryCandidates
                .groupBy { documentFamilyKey(it.credentialType) }
                .mapNotNull { (_, familyCandidates) ->
                    familyCandidates.maxByOrNull { it.credentialId ?: Long.MIN_VALUE }
                }
        }

private fun documentFamilyKey(credentialType: String): String = when {
    CredentialTypeLabels.isPidType(credentialType) -> "pid"
    CredentialTypeLabels.isMdlType(credentialType) -> "mdl"
    else -> credentialType.lowercase()
}
