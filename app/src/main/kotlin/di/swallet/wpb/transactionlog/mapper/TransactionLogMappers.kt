/**
 * Maps wallet domain events to normative TS10 transaction payloads.
 */

package di.swallet.wpb.transactionlog.mapper

import di.swallet.wpb.config.DataDeletionRequestProperties
import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.ProviderFallbackDpa
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
import di.swallet.wpb.presentation.domain.SupervisoryAuthorityContact
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

/** Builds TS10 Presentation transactions from presentation sessions or legacy controller calls. */
@Component
class PresentationTransactionMapper(
    private val contactBuilder: Ts10InteractingPartyContactBuilder,
    private val dpaContactBuilder: Ts10DpaContactBuilder,
    private val rpDnsNameResolver: RpDnsNameResolver,
    private val openId4VpProperties: OpenId4VpProperties,
    private val dataDeletionProperties: DataDeletionRequestProperties,
    private val dpaReportProperties: DpaReportProperties,
) {
    /** Maps a terminal presentation context to a TS10 Presentation transaction. Returns null when not loggable. */
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

        val authRequest = context.authorizationRequest

        val presentation = Ts10Presentation(
            interactingPartyIdentifier = registry?.identifier?.let {
                Ts10Identifier(type = "http://data.europa.eu/eudi/id/EUID", identifier = it)
            } ?: authRequest?.clientId?.takeIf { it.isNotBlank() }?.let {
                Ts10Identifier(type = "openid_client_id", identifier = it)
            },
            interactingPartyName = registry?.tradeName
                ?: context.verifierIdentity?.displayName
                ?: authRequest?.clientId,
            interactingPartyContact = buildContact(registry),
            registrarURL = registry?.registryUri
                ?: context.registryDecision?.sourceEndpoint?.takeIf { it.isNotBlank() }
                ?: authRequest?.requestUri,
            purpose = intendedUse?.purpose.orEmpty().map { Ts10MultiLangString(content = it) },
            privacyPolicy = intendedUse?.privacyPolicyUris?.firstOrNull()?.let {
                Ts10Policy(
                    type = "http://data.europa.eu/eudi/policy/privacy-policy",
                    policyUri = it,
                )
            },
            dpaName = resolveDpaName(registry),
            dpaCountry = resolveDpaCountry(registry),
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

    /** Builds a Presentation transaction from legacy wallet controller parameters. */
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

    /** Returns true for terminal presentation states that should be logged. */
    private fun isLoggableTerminal(state: PresentationState): Boolean =
        state in setOf(
            PresentationState.DISPATCHED,
            PresentationState.FAILED,
            PresentationState.REJECTED,
            PresentationState.EXPIRED,
        )

    /** Collects requested claims from credential queries or candidate groupings. */
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

    /** Maps a single credential query to TS10 claim info. */
    private fun claimInfoFromQuery(query: CredentialQuery): List<Ts10ClaimInfo> {
        val credentialId = query.credentialTypeHints.firstOrNull() ?: query.id
        return listOf(
            Ts10ClaimInfo(
                credentialIdentifier = credentialId,
                claims = query.requestedClaims,
            ),
        )
    }

    /** Collects presented claims from selected credentials or candidate fallbacks. */
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

    /** Builds interacting-party contact strings from RP registry support URIs or demo fallbacks. */
    private fun buildContact(registry: RpRegistryRecord?): List<String> {
        if (registry != null) {
            val fromRegistry = contactBuilder.fromRegistry(registry)
            if (fromRegistry.isNotEmpty()) return fromRegistry
        }
        return demoRpDeletionContacts()
    }

    /** Builds DPA contact strings from registry data or demo fallbacks. */
    private fun buildDpaContact(dpa: SupervisoryAuthorityContact?): List<String> {
        if (dpa != null) {
            val fromRegistry = dpaContactBuilder.fromSupervisoryAuthority(dpa)
            if (fromRegistry.isNotEmpty()) return fromRegistry
        }
        return demoDpaContacts()
    }

    private fun resolveDpaName(registry: RpRegistryRecord?): String? =
        registry?.supervisoryAuthority?.name?.takeIf { it.isNotBlank() }
            ?: demoDpaValue { it.name.takeIf { name -> name.isNotBlank() } }

    private fun resolveDpaCountry(registry: RpRegistryRecord?): String? =
        registry?.supervisoryAuthority?.country?.takeIf { it.isNotBlank() }
            ?: demoDpaValue { it.country.takeIf { country -> country.isNotBlank() } }

    private fun demoRpDeletionContacts(): List<String> {
        if (!openId4VpProperties.demoMode) return emptyList()
        val fallback = dataDeletionProperties.providerFallbackRp
        if (!fallback.hasDeletionChannel()) return emptyList()
        return contactBuilder.fromSupportUris(
            country = fallback.country.takeIf { it.isNotBlank() },
            supportUris = listOfNotNull(
                fallback.email.takeIf { it.isNotBlank() },
                fallback.phone.takeIf { it.isNotBlank() },
                fallback.webUri.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun demoDpaContacts(): List<String> {
        if (!openId4VpProperties.demoMode) return emptyList()
        val fallback = dpaReportProperties.providerFallbackDpa
        if (!fallback.hasContactChannel()) return emptyList()
        return dpaContactBuilder.fromSupervisoryAuthority(
            SupervisoryAuthorityContact(
                name = fallback.name.takeIf { it.isNotBlank() },
                country = fallback.country.takeIf { it.isNotBlank() },
                email = listOfNotNull(fallback.email.takeIf { it.isNotBlank() }),
                phone = listOfNotNull(fallback.phone.takeIf { it.isNotBlank() }),
                formUri = listOfNotNull(fallback.formUri.takeIf { it.isNotBlank() }),
            ),
        )
    }

    private fun demoDpaValue(selector: (ProviderFallbackDpa) -> String?): String? {
        if (!openId4VpProperties.demoMode) return null
        return selector(dpaReportProperties.providerFallbackDpa)
    }
}

/** Builds TS10 CredentialIssuance transactions from issuance sessions or legacy calls. */
@Component
class IssuanceTransactionMapper {
    /** Maps a terminal or partially issued issuance context to a TS10 CredentialIssuance transaction. */
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

    /** Builds a completed CredentialIssuance transaction for the legacy issuance path. */
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

/** Builds TS10 CredentialDeletion transactions when a credential is removed from the wallet. */
@Component
class CredentialDeletionTransactionMapper(
    private val credentialIssuerResolver: CredentialIssuerResolver,
) {
    /** Maps a wallet credential to a completed CredentialDeletion transaction. */
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

/** Builds TS10 SigningSealing transactions for document signing operations. */
@Component
class SigningTransactionMapper(
    private val crypto: TransactionLogCrypto,
) {
    /** Maps a sign operation to a SigningSealing transaction with SHA-256 content hash. */
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
