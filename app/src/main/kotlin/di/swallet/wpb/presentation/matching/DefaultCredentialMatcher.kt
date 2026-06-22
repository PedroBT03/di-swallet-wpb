/**
 * Default credential matching against verifier DCQL queries.
 */

package di.swallet.wpb.presentation.matching

import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocEffectiveDocTypeResolver
import di.swallet.wpb.format.sdjwt.SdJwtDisclosureSelector
import di.swallet.wpb.openid4vp.protocol.DcqlSupport
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.revocation.CredentialRevocationGuard
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Matches stored wallet credentials to verifier DCQL queries.
 * SD-JWT and mdoc formats are supported; demo mode may add synthetic candidates.
 */
@Service
class DefaultCredentialMatcher(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val mdocCredentialCodec: MdocCredentialCodec,
    private val mdocDocTypeRegistry: MdocDocTypeRegistry,
    private val mdocEffectiveDocTypeResolver: MdocEffectiveDocTypeResolver,
    private val disclosureCipherService: DisclosureCipherService,
    private val sdJwtDisclosureSelector: SdJwtDisclosureSelector,
    @param:Value("\${wpb.openid4vp.demo-mode:false}") private val demoMode: Boolean,
    private val credentialRevocationGuard: CredentialRevocationGuard,
) : CredentialMatcher {

    /** Parses DCQL queries, searches the wallet, and attaches matching candidates to the context. */
    override fun match(context: PresentationContext): PresentationContext {
        val effectiveQueries = resolveQueries(context)

        val holderId = context.sessionMeta.holderId
        val storedCredentials = loadWalletCredentials(holderId)

        val candidates = effectiveQueries.flatMap { query ->
            matchQueryAgainstWallet(query, storedCredentials, RevocationScope.ACTIVE_ONLY)
                .ifEmpty { syntheticCandidate(query, holderId) }
        }

        return context.copy(
            credentialCandidates = candidates,
            presentationRequirements = context.presentationRequirements?.copy(credentialQueries = effectiveQueries)
                ?: context.authorizationRequest?.requirements?.copy(credentialQueries = effectiveQueries),
        )
    }

    /** Returns true when revoked wallet credentials would satisfy the verifier DCQL queries. */
    override fun hasRevokedMatches(context: PresentationContext): Boolean {
        val effectiveQueries = resolveQueries(context)
        val storedCredentials = loadWalletCredentials(context.sessionMeta.holderId)
        return effectiveQueries.any { query ->
            matchQueryAgainstWallet(query, storedCredentials, RevocationScope.REVOKED_ONLY).isNotEmpty()
        }
    }

    private fun resolveQueries(context: PresentationContext): List<CredentialQuery> {
        val requirements = context.authorizationRequest?.requirements
            ?: context.presentationRequirements
        val parsedQueries = requirements?.credentialQueries?.takeIf { it.isNotEmpty() }
            ?: DcqlSupport.parse(requirements?.dcqlQueryJson)
        return parsedQueries.ifEmpty {
            requirements?.credentialQueryIds?.map { CredentialQuery(id = it, format = CredentialFormat.SD_JWT) }
                ?: listOf(CredentialQuery(id = "query_0", format = CredentialFormat.SD_JWT))
        }
    }

    private fun loadWalletCredentials(holderId: String?): List<di.swallet.wpb.domain.WalletCredential> =
        when {
            holderId.isNullOrBlank() -> walletCredentialRepository.findAll()
            else -> walletCredentialRepository.findByUserId(holderId)
        }

    private enum class RevocationScope {
        ACTIVE_ONLY,
        REVOKED_ONLY,
    }

    /** Matches one DCQL query against wallet credentials filtered by revocation scope. */
    private fun matchQueryAgainstWallet(
        query: CredentialQuery,
        wallet: List<di.swallet.wpb.domain.WalletCredential>,
        revocationScope: RevocationScope,
    ): List<CredentialCandidate> {
        val scopedWallet = wallet.filter { credential ->
            val revoked = credentialRevocationGuard.isRevoked(credential)
            when (revocationScope) {
                RevocationScope.ACTIVE_ONLY -> !revoked
                RevocationScope.REVOKED_ONLY -> revoked
            }
        }
        return when (query.format) {
            CredentialFormat.SD_JWT -> matchSdJwt(query, scopedWallet)
            CredentialFormat.MDOC -> matchMdoc(query, scopedWallet)
        }
    }

    /** Finds SD-JWT credentials whose disclosures can satisfy the requested claim paths. */
    private fun matchSdJwt(
        query: CredentialQuery,
        wallet: List<di.swallet.wpb.domain.WalletCredential>,
    ): List<CredentialCandidate> {
        val typeFilter: (di.swallet.wpb.domain.WalletCredential) -> Boolean = { credential ->
            CredentialTypeHintMatcher.sdJwtTypeMatches(
                hints = query.credentialTypeHints,
                credentialType = credential.credentialType,
                issuerJwt = credential.encodedData,
            )
        }
        return wallet.asSequence()
            .filter(typeFilter)
            .filter { credential -> sdJwtCredentialSatisfiesQuery(credential, query) }
            .mapNotNull { credential ->
                val credentialPk = credential.id ?: return@mapNotNull null
                CredentialCandidate(
                    candidateId = "query:${query.id}:credential:$credentialPk",
                    credentialId = credentialPk,
                    holderId = credential.userId,
                    queryId = query.id,
                    credentialType = credential.credentialType,
                    format = CredentialFormat.SD_JWT,
                    requestedClaimPaths = query.requestedClaimPaths,
                )
            }
            .toList()
    }

    /** Returns true when the credential disclosures cover all requested claim paths. */
    private fun sdJwtCredentialSatisfiesQuery(
        credential: di.swallet.wpb.domain.WalletCredential,
        query: CredentialQuery,
    ): Boolean {
        if (query.requestedClaimPaths.isEmpty()) return true
        val disclosures = loadStoredDisclosures(credential)
        return sdJwtDisclosureSelector.canSatisfy(disclosures, query.requestedClaimPaths)
    }

    /** Loads SD-JWT disclosures from inline payload data or encrypted storage. */
    private fun loadStoredDisclosures(credential: di.swallet.wpb.domain.WalletCredential): List<String> {
        if (credential.encodedData.contains('~')) {
            return credential.encodedData.split('~').drop(1).filter { it.isNotBlank() }
        }
        if (credential.encryptedDisclosures.isBlank()) return emptyList()
        return disclosureCipherService.decrypt(credential.encryptedDisclosures)
    }

    /** Finds mdoc credentials whose doc type and available claims satisfy the query. */
    private fun matchMdoc(
        query: CredentialQuery,
        wallet: List<di.swallet.wpb.domain.WalletCredential>,
    ): List<CredentialCandidate> {
        return wallet.asSequence()
            .mapNotNull { credential ->
                val credentialPk = credential.id ?: return@mapNotNull null
                val decoded = mdocCredentialCodec.decode(credential.encodedData) ?: return@mapNotNull null
                val effectiveDocType = mdocEffectiveDocTypeResolver.resolve(credential.credentialType, decoded)
                val docTypeMatches = CredentialTypeHintMatcher.mdocTypeMatches(
                    hints = query.credentialTypeHints,
                    docType = effectiveDocType,
                )
                if (!docTypeMatches) return@mapNotNull null
                val definition = mdocDocTypeRegistry.resolve(effectiveDocType)
                val canonicalRequested = query.requestedClaimPaths.map { path ->
                    val claim = path.toDotNotation()
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
                    requestedClaimPaths = query.requestedClaimPaths,
                )
            }
            .toList()
    }

    /** Returns one synthetic candidate per query when demo mode is enabled. */
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
                requestedClaimPaths = query.requestedClaimPaths,
            ),
        )
    }
}
