package di.swallet.wpb.presentation.format

import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.presentation.domain.VpToken
import org.springframework.stereotype.Service

@Service
class DefaultVpTokenBuilder : VpTokenBuilder {
    override fun build(context: PresentationContext): PresentationContext {
        val selected = if (context.selectedCredentials.isNotEmpty()) {
            context.selectedCredentials
        } else {
            context.credentialCandidates.firstOrNull()?.let { candidate ->
                listOf(
                    SelectedCredential(
                        candidateId = candidate.candidateId,
                        credentialId = candidate.credentialId,
                        holderId = candidate.holderId,
                        queryId = candidate.queryId,
                        credentialType = candidate.credentialType,
                        format = candidate.format,
                    ),
                )
            }.orEmpty()
        }

        val presentationsByQueryId = if (selected.isEmpty()) {
            emptyMap()
        } else {
            selected.groupBy { it.queryId }.mapValues { (_, items) ->
                items.map { "stub-vp:${it.candidateId}" }
            }
        }

        val token = VpToken(
            presentationsByQueryId = presentationsByQueryId,
            format = selected.firstOrNull()?.format ?: CredentialFormat.SD_JWT,
            rawValue = presentationsByQueryId.toString(),
        )

        return context.copy(
            selectedCredentials = selected,
            vpToken = token,
        )
    }
}
