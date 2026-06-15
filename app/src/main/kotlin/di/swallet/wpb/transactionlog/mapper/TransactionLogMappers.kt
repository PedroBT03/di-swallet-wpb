package di.swallet.wpb.transactionlog.mapper

import di.swallet.wpb.datadeletion.Ts10InteractingPartyContactBuilder
import di.swallet.wpb.dpareport.RpDnsNameResolver
import di.swallet.wpb.dpareport.Ts10DpaContactBuilder
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.RpRegistryRecord
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import di.swallet.wpb.transactionlog.domain.Ts10CredentialDeletion
import di.swallet.wpb.transactionlog.domain.Ts10CredentialIssuance
import di.swallet.wpb.transactionlog.domain.Ts10Identifier
import di.swallet.wpb.transactionlog.domain.Ts10MultiLangString
import di.swallet.wpb.transactionlog.domain.Ts10Policy
import di.swallet.wpb.transactionlog.domain.Ts10Presentation
import di.swallet.wpb.transactionlog.domain.Ts10SigningSealing
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.CredentialIssuerResolver
import di.swallet.wpb.transactionlog.Ts10InstantFormatter
import di.swallet.wpb.transactionlog.crypto.TransactionLogCrypto
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class PresentationTransactionMapper(
    private val contactBuilder: Ts10InteractingPartyContactBuilder,
    private val dpaContactBuilder: Ts10DpaContactBuilder,
    private val rpDnsNameResolver: RpDnsNameResolver,
) {
    fun fromContext(context: PresentationContext, now: Instant = Instant.now()): Ts10Transaction? {
        val holderId = context.sessionMeta.holderId ?: return null
        if (holderId.isBlank()) return null
        if (!isLoggableTerminal(context.state)) return null

        val completed = context.state == PresentationState.DISPATCHED &&
            context.consentDecision?.granted == true &&
            context.error == null

        val requested = buildRequestedClaims(context)
        val presented = if (completed) buildPresentedClaims(context) else emptyList()

        val registry = context.registryRecord
        val intendedUse = registry?.intendedUses?.firstOrNull()
        val dpa = registry?.supervisoryAuthority

        val presentation = Ts10Presentation(
            interactingPartyIdentifier = registry?.identifier?.let {
                Ts10Identifier(type = "http://data.europa.eu/eudi/id/EUID", identifier = it)
            },
            interactingPartyName = registry?.tradeName ?: context.verifierIdentity?.displayName,
            interactingPartyContact = buildContact(registry),
            registrarURL = registry?.registryUri ?: context.registryDecision?.sourceEndpoint,
            purpose = intendedUse?.purpose.orEmpty().map { Ts10MultiLangString(content = it) },
            privacyPolicy = intendedUse?.privacyPolicyUris?.firstOrNull()?.let {
                Ts10Policy(
                    type = "http://data.europa.eu/eudi/policy/privacy-policy",
                    policyUri = it,
                )
            },
            dpaName = dpa?.name,
            dpaCountry = dpa?.country,
            dpaContact = buildDpaContact(dpa),
            rpDnsName = rpDnsNameResolver.fromContext(context),
            listOfClaimsRequested = requested,
            listOfClaimsPresented = presented,
            reasonOfNoncompletion = if (completed) null else context.error?.message ?: context.consentDecision?.reason,
        )

        return Ts10Transaction(
            transactionIdentifier = context.sessionMeta.correlationId.ifBlank { UUID.randomUUID().toString() },
            time = Ts10InstantFormatter.format(now),
            transactionType = Ts10TransactionType.Presentation.name,
            transactionResult = if (completed) Ts10TransactionResult.Completed.name else Ts10TransactionResult.NotCompleted.name,
            presentation = presentation,
        )
    }

    fun fromLegacyPresentation(
        holderId: String,
        credentialType: String,
        claimsRequested: List<String>,
        claimsPresented: List<String>,
        completed: Boolean,
        reason: String? = null,
        rpName: String? = null,
        now: Instant = Instant.now(),
    ): Ts10Transaction {
        val presentation = Ts10Presentation(
            interactingPartyName = rpName ?: "Legacy presentation",
            registrarURL = "legacy://wallet-controller",
            listOfClaimsRequested = listOf(
                Ts10ClaimInfo(credentialIdentifier = credentialType, claims = claimsRequested),
            ),
            listOfClaimsPresented = listOf(
                Ts10ClaimInfo(credentialIdentifier = credentialType, claims = claimsPresented),
            ),
            reasonOfNoncompletion = reason,
        )
        return Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = Ts10InstantFormatter.format(now),
            transactionType = Ts10TransactionType.Presentation.name,
            transactionResult = if (completed) Ts10TransactionResult.Completed.name else Ts10TransactionResult.NotCompleted.name,
            presentation = presentation,
        )
    }

    private fun isLoggableTerminal(state: PresentationState): Boolean =
        state in setOf(
            PresentationState.DISPATCHED,
            PresentationState.FAILED,
            PresentationState.REJECTED,
            PresentationState.EXPIRED,
        )

    private fun buildRequestedClaims(context: PresentationContext): List<Ts10ClaimInfo> {
        val queries = context.presentationRequirements?.credentialQueries.orEmpty()
        if (queries.isNotEmpty()) {
            return queries.flatMap { query -> claimInfoFromQuery(query) }
        }
        return context.credentialCandidates
            .groupBy { it.credentialType }
            .map { (type, candidates) ->
                Ts10ClaimInfo(
                    credentialIdentifier = type,
                    claims = candidates.flatMap { it.requestedClaims }.distinct(),
                )
            }
    }

    private fun claimInfoFromQuery(query: CredentialQuery): List<Ts10ClaimInfo> {
        val credentialId = query.credentialTypeHints.firstOrNull() ?: query.id
        return listOf(
            Ts10ClaimInfo(
                credentialIdentifier = credentialId,
                claims = query.requestedClaims,
            ),
        )
    }

    private fun buildPresentedClaims(context: PresentationContext): List<Ts10ClaimInfo> {
        val selected = context.selectedCredentials
        if (selected.isNotEmpty()) {
            return selected.map { credential ->
                Ts10ClaimInfo(
                    credentialIdentifier = credential.credentialType,
                    claims = credential.requestedClaims,
                )
            }
        }
        return context.credentialCandidates
            .groupBy { it.credentialType }
            .map { (type, candidates) ->
                Ts10ClaimInfo(
                    credentialIdentifier = type,
                    claims = candidates.flatMap { it.requestedClaims }.distinct(),
                )
            }
    }

    private fun buildContact(registry: RpRegistryRecord?): List<String> {
        if (registry == null) return emptyList()
        return contactBuilder.fromRegistry(registry)
    }

    private fun buildDpaContact(dpa: di.swallet.wpb.presentation.domain.SupervisoryAuthorityContact?): List<String> {
        if (dpa == null) return emptyList()
        return dpaContactBuilder.fromSupervisoryAuthority(dpa)
    }
}

@Component
class IssuanceTransactionMapper {
    fun fromContext(
        context: IssuanceContext,
        issued: List<IssuedCredential> = emptyList(),
        now: Instant = Instant.now(),
    ): Ts10Transaction? {
        val holderId = context.sessionMeta.holderId ?: return null
        if (holderId.isBlank()) return null
        if (!context.state.isTerminal && issued.isEmpty()) return null

        val metadata = context.issuerMetadata
        val requested = context.credentialConfigurationIds.size.coerceAtLeast(1)
        val issuedCount = issued.size
        val completed = issuedCount > 0 && context.error == null &&
            context.state in setOf(IssuanceState.CREDENTIAL_ISSUED, IssuanceState.DEFERRED_ISSUED, IssuanceState.NOTIFIED)

        val issuance = Ts10CredentialIssuance(
            interactingPartyIdentifier = metadata?.credentialIssuerId?.let {
                Ts10Identifier(type = "http://data.europa.eu/eudi/id/LEI", identifier = it)
            },
            interactingPartyName = metadata?.credentialIssuerId,
            interactingPartyContact = emptyList(),
            credentialNumberRequested = requested,
            credentialNumberIssued = issuedCount,
            credentialIdentifier = issued.map { it.credentialConfigurationId.ifBlank { it.format.name } },
            isUserTriggered = "TRUE",
            reasonOfNoncompletion = if (completed) null else context.error?.message,
        )

        return Ts10Transaction(
            transactionIdentifier = context.sessionMeta.correlationId,
            time = Ts10InstantFormatter.format(now),
            transactionType = Ts10TransactionType.CredentialIssuance.name,
            transactionResult = if (completed) Ts10TransactionResult.Completed.name else Ts10TransactionResult.NotCompleted.name,
            credentialIssuance = issuance,
        )
    }

    fun fromLegacyIssuance(
        holderId: String,
        credentialType: String,
        issuerName: String,
        issuerId: String,
        now: Instant = Instant.now(),
    ): Ts10Transaction = Ts10Transaction(
        transactionIdentifier = UUID.randomUUID().toString(),
        time = Ts10InstantFormatter.format(now),
        transactionType = Ts10TransactionType.CredentialIssuance.name,
        transactionResult = Ts10TransactionResult.Completed.name,
        credentialIssuance = Ts10CredentialIssuance(
            interactingPartyIdentifier = Ts10Identifier(
                type = "http://data.europa.eu/eudi/id/EUID",
                identifier = issuerId,
            ),
            interactingPartyName = issuerName,
            credentialNumberRequested = 1,
            credentialNumberIssued = 1,
            credentialIdentifier = listOf(credentialType),
            isUserTriggered = "TRUE",
        ),
    )
}

@Component
class CredentialDeletionTransactionMapper(
    private val credentialIssuerResolver: CredentialIssuerResolver,
) {
    fun fromCredential(credential: WalletCredential, now: Instant = Instant.now()): Ts10Transaction {
        val issuer = credentialIssuerResolver.resolve(credential)
        return Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = Ts10InstantFormatter.format(now),
            transactionType = Ts10TransactionType.CredentialDeletion.name,
            transactionResult = Ts10TransactionResult.Completed.name,
            credentialDeletion = Ts10CredentialDeletion(
                credentialIdentifier = credential.credentialType,
                credentialIssuerIdentifier = issuer.identifier,
                credentialIssuerName = issuer.name,
            ),
        )
    }
}

@Component
class SigningTransactionMapper(
    private val crypto: TransactionLogCrypto,
) {
    fun fromSignOperation(
        payload: ByteArray,
        algorithm: String,
        completed: Boolean,
        reason: String? = null,
        now: Instant = Instant.now(),
    ): Ts10Transaction = Ts10Transaction(
        transactionIdentifier = UUID.randomUUID().toString(),
        time = Ts10InstantFormatter.format(now),
        transactionType = Ts10TransactionType.SigningSealing.name,
        transactionResult = if (completed) Ts10TransactionResult.Completed.name else Ts10TransactionResult.NotCompleted.name,
        signingSealing = Ts10SigningSealing(
            contentHash = crypto.contentHash(payload),
            hashAlgorithm = "SHA-256",
            signatureAlgorithm = algorithm,
            reasonOfNoncompletion = reason,
        ),
    )
}
