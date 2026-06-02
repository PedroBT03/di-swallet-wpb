package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.openid4vp.protocol.DcqlSupport
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.PresentationContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Matches wallet credentials against the verifier DCQL queries.
 *
 *  - Only the SD-JWT format is supported at runtime. MDOC queries are matched
 *    only when synthetic demo data is allowed.
 *  - Type hints (`vct_values`) are used as a soft filter when present.
 *  - Per-query requested claim names are attached to each candidate so the VP
 *    builder can filter selective disclosures accordingly.
 */
@Service
class DefaultCredentialMatcher(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val mdocCredentialCodec: MdocCredentialCodec,
    private val mdocDocTypeRegistry: MdocDocTypeRegistry,
    @param:Value("\${wpb.openid4vp.demo-mode:false}") private val demoMode: Boolean,
) : CredentialMatcher {

    override fun match(context: PresentationContext): PresentationContext {
        val requirements = context.authorizationRequest?.requirements
        val parsedQueries = requirements?.credentialQueries?.takeIf { it.isNotEmpty() }
            ?: DcqlSupport.parse(requirements?.dcqlQueryJson)
        val effectiveQueries = parsedQueries.ifEmpty {
            requirements?.credentialQueryIds?.map { CredentialQuery(id = it, format = CredentialFormat.SD_JWT) }
                ?: listOf(CredentialQuery(id = "query_0", format = CredentialFormat.SD_JWT))
        }

        val holderId = context.sessionMeta.holderId
        val storedCredentials = when {
            holderId.isNullOrBlank() -> walletCredentialRepository.findAll()
            else -> walletCredentialRepository.findByUserId(holderId)
        }

        val candidates = effectiveQueries.flatMap { query ->
            matchQueryAgainstWallet(query, storedCredentials)
                .ifEmpty { syntheticCandidate(query, holderId) }
        }

        return context.copy(
            credentialCandidates = candidates,
            presentationRequirements = requirements?.copy(credentialQueries = effectiveQueries),
        )
    }

    private fun matchQueryAgainstWallet(
        query: CredentialQuery,
        wallet: List<di.swallet.wpb.domain.WalletCredential>,
    ): List<CredentialCandidate> {
        return when (query.format) {
            CredentialFormat.SD_JWT -> matchSdJwt(query, wallet)
            CredentialFormat.MDOC -> matchMdoc(query, wallet)
        }
    }

    private fun matchSdJwt(
        query: CredentialQuery,
        wallet: List<di.swallet.wpb.domain.WalletCredential>,
    ): List<CredentialCandidate> {
        val typeFilter: (di.swallet.wpb.domain.WalletCredential) -> Boolean = { credential ->
            query.credentialTypeHints.isEmpty() ||
                query.credentialTypeHints.any { hint -> hint.equals(credential.credentialType, ignoreCase = true) }
        }
        return wallet.asSequence()
            .filter(typeFilter)
            .mapNotNull { credential ->
                val credentialPk = credential.id ?: return@mapNotNull null
                CredentialCandidate(
                    candidateId = "query:${query.id}:credential:$credentialPk",
                    credentialId = credentialPk,
                    holderId = credential.userId,
                    queryId = query.id,
                    credentialType = credential.credentialType,
                    format = CredentialFormat.SD_JWT,
                    requestedClaims = query.requestedClaims,
                )
            }
            .toList()
    }

    private fun matchMdoc(
        query: CredentialQuery,
        wallet: List<di.swallet.wpb.domain.WalletCredential>,
    ): List<CredentialCandidate> {
        return wallet.asSequence()
            .mapNotNull { credential ->
                val credentialPk = credential.id ?: return@mapNotNull null
                val decoded = mdocCredentialCodec.decode(credential.encodedData) ?: return@mapNotNull null
                val effectiveDocType = if (decoded.docType == "unknown") credential.credentialType else decoded.docType
                val docTypeMatches = query.credentialTypeHints.isEmpty() ||
                    query.credentialTypeHints.any { hint -> hint.equals(effectiveDocType, ignoreCase = true) }
                if (!docTypeMatches) return@mapNotNull null
                val definition = mdocDocTypeRegistry.resolve(effectiveDocType)
                val canonicalRequested = query.requestedClaims.map { claim ->
                    definition?.claimMapping?.get(claim) ?: definition?.claimMapping?.get(claim.lowercase()) ?: claim
                }
                val availableClaimNames = decoded.claims.keys.map { it.substringAfterLast('.') }.toSet()
                val claimsSatisfied = canonicalRequested.isEmpty() || canonicalRequested.all {
                    it in availableClaimNames || it in decoded.claims.keys
                }
                if (!claimsSatisfied) return@mapNotNull null
                CredentialCandidate(
                    candidateId = "query:${query.id}:credential:$credentialPk",
                    credentialId = credentialPk,
                    holderId = credential.userId,
                    queryId = query.id,
                    credentialType = effectiveDocType,
                    format = CredentialFormat.MDOC,
                    requestedClaims = canonicalRequested,
                )
            }
            .toList()
    }

    private fun syntheticCandidate(query: CredentialQuery, holderId: String?): List<CredentialCandidate> {
        if (!demoMode) return emptyList()
        return listOf(
            CredentialCandidate(
                candidateId = "demo:${query.id}",
                credentialId = null,
                holderId = holderId ?: "demo-holder",
                queryId = query.id,
                credentialType = query.credentialTypeHints.firstOrNull() ?: "DemoCredential",
                format = query.format,
                requestedClaims = query.requestedClaims,
            ),
        )
    }
}
