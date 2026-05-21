package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class DefaultCredentialMatcher(
    private val walletCredentialRepository: WalletCredentialRepository,
    @param:Value("\${wpb.openid4vp.demo-mode:false}") private val demoMode: Boolean,
) : CredentialMatcher {
    override fun match(context: PresentationContext): PresentationContext {
        val holderId = context.sessionMeta.holderId
        val credentials = if (holderId.isNullOrBlank()) {
            walletCredentialRepository.findAll()
        } else {
            walletCredentialRepository.findByUserId(holderId)
        }

        val queryIds = context.authorizationRequest
            ?.requirements
            ?.credentialQueryIds
            .orEmpty()
            .ifEmpty { listOf("query_0") }

        val candidates = credentials.flatMap { credential ->
            val credentialPk = credential.id ?: return@flatMap emptyList<CredentialCandidate>()
            val credentialFormat = CredentialFormat.SD_JWT
            queryIds.map { queryId ->
                CredentialCandidate(
                    candidateId = "query:$queryId:credential:$credentialPk",
                    credentialId = credentialPk,
                    holderId = credential.userId,
                    queryId = queryId,
                    credentialType = credential.credentialType,
                    format = credentialFormat,
                )
            }
        }

        val effectiveCandidates = if (candidates.isNotEmpty() || !demoMode) {
            candidates
        } else {
            queryIds.map { queryId ->
                CredentialCandidate(
                    candidateId = "demo:$queryId",
                    credentialId = null,
                    holderId = holderId ?: "demo-holder",
                    queryId = queryId,
                    credentialType = "DemoCredential",
                    format = CredentialFormat.SD_JWT,
                )
            }
        }

        return context.copy(credentialCandidates = effectiveCandidates)
    }
}
