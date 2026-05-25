package di.swallet.wpb.issuance.domain

import di.swallet.wpb.openid4vci.protocol.AuthorizationFlowKind
import di.swallet.wpb.openid4vci.protocol.AuthorizedContext
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.openid4vci.protocol.PreparedAuthorization
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.openid4vci.protocol.ResolvedOffer
import java.time.Instant
import java.util.UUID

/**
 * Credential formats supported.
 *
 * Only `SD_JWT_VC` is exercised end-to-end. `MSO_MDOC` is declared so the
 * matching/policy code can recognise issuer-advertised mdoc configurations
 * but the orchestrator refuses to complete a real issuance for it (see
 * [DefaultIssuanceFlowOrchestrator]).
 */
enum class IssuanceCredentialFormat {
    SD_JWT_VC,
    MSO_MDOC,
    UNKNOWN,
}

/**
 * Formal lifecycle of an OID4VCI issuance session.
 */
enum class IssuanceState {
    OFFER_RECEIVED,
    OFFER_RESOLVED,
    AUTHORIZATION_PREPARED,
    AUTHORIZED,
    CREDENTIAL_REQUESTED,
    CREDENTIAL_ISSUED,
    DEFERRED_PENDING,
    DEFERRED_ISSUED,
    NOTIFIED,
    FAILED,
    REJECTED,
    EXPIRED,
    ;

    val isTerminal: Boolean
        get() = this in setOf(NOTIFIED, FAILED, REJECTED, EXPIRED)

    fun canTransitionTo(next: IssuanceState): Boolean = when (this) {
        OFFER_RECEIVED -> next in setOf(OFFER_RESOLVED, FAILED, REJECTED, EXPIRED)
        OFFER_RESOLVED -> next in setOf(AUTHORIZATION_PREPARED, AUTHORIZED, FAILED, REJECTED, EXPIRED)
        AUTHORIZATION_PREPARED -> next in setOf(AUTHORIZED, FAILED, REJECTED, EXPIRED)
        AUTHORIZED -> next in setOf(CREDENTIAL_REQUESTED, FAILED, REJECTED, EXPIRED)
        CREDENTIAL_REQUESTED -> next in setOf(CREDENTIAL_ISSUED, DEFERRED_PENDING, FAILED, REJECTED, EXPIRED)
        CREDENTIAL_ISSUED -> next in setOf(NOTIFIED, FAILED, EXPIRED)
        DEFERRED_PENDING -> next in setOf(DEFERRED_PENDING, DEFERRED_ISSUED, CREDENTIAL_ISSUED, FAILED, REJECTED, EXPIRED)
        DEFERRED_ISSUED -> next in setOf(NOTIFIED, FAILED, EXPIRED)
        NOTIFIED -> next == EXPIRED
        FAILED -> next == EXPIRED
        REJECTED -> next == EXPIRED
        EXPIRED -> next == EXPIRED
    }
}

/**
 * Metadata that scopes a single issuance lifecycle.
 *
 * `correlationId` mirrors the Phase 1 conventions, so logs and events
 * remain traceable across phases.
 */
data class IssuanceSessionMetadata(
    val sessionId: UUID,
    val holderId: String? = null,
    val correlationId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val version: Long = 0,
)

/**
 * Internal issuance error carried through the lifecycle.
 */
data class IssuanceError(
    val code: String,
    val message: String,
    val recoverable: Boolean = false,
)

/**
 * Records the wallet's decision about a verifier/issuer/metadata.
 */
data class IssuanceTrustDecision(
    val trusted: Boolean,
    val reason: String? = null,
)

/**
 * Wallet policy decision for an issuance session.
 */
data class IssuancePolicyDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

/**
 * Identifier of a deferred issuance result returned by the credential issuer.
 *
 * `serializedContext` is the EUDI SDK's `DeferredIssuanceContext` serialised by
 * the adapter so the wallet can suspend/resume issuance across processes.
 */
data class DeferredIssuanceHandle(
    val transactionId: String,
    val notificationId: String? = null,
    val serializedContext: String? = null,
)

/**
 * Runtime container that travels through the orchestrator.
 */
data class IssuanceContext(
    val sessionMeta: IssuanceSessionMetadata,
    val state: IssuanceState,
    val flow: AuthorizationFlowKind? = null,
    val offerUri: String? = null,
    val credentialIssuerId: String? = null,
    val credentialConfigurationIds: List<String> = emptyList(),
    val resolvedOffer: ResolvedOffer? = null,
    val issuerMetadata: ResolvedIssuerMetadata? = null,
    val trustDecision: IssuanceTrustDecision? = null,
    val policyDecision: IssuancePolicyDecision? = null,
    val preparedAuthorization: PreparedAuthorization? = null,
    val authorizedContext: AuthorizedContext? = null,
    val deferredHandle: DeferredIssuanceHandle? = null,
    val issuedCredentials: List<IssuedCredential> = emptyList(),
    val notificationOutcome: String? = null,
    val error: IssuanceError? = null,
)

/**
 * Persisted representation of an issuance session.
 */
data class IssuanceSession(
    val sessionMeta: IssuanceSessionMetadata,
    val state: IssuanceState,
    val flow: AuthorizationFlowKind? = null,
    val offerUri: String? = null,
    val credentialIssuerId: String? = null,
    val credentialConfigurationIds: List<String> = emptyList(),
    val resolvedOffer: ResolvedOffer? = null,
    val issuerMetadata: ResolvedIssuerMetadata? = null,
    val trustDecision: IssuanceTrustDecision? = null,
    val policyDecision: IssuancePolicyDecision? = null,
    val preparedAuthorization: PreparedAuthorization? = null,
    val authorizedContext: AuthorizedContext? = null,
    val deferredHandle: DeferredIssuanceHandle? = null,
    val issuedCredentials: List<IssuedCredential> = emptyList(),
    val notificationOutcome: String? = null,
    val error: IssuanceError? = null,
)

fun IssuanceContext.toSession(): IssuanceSession = IssuanceSession(
    sessionMeta = sessionMeta,
    state = state,
    flow = flow,
    offerUri = offerUri,
    credentialIssuerId = credentialIssuerId,
    credentialConfigurationIds = credentialConfigurationIds,
    resolvedOffer = resolvedOffer,
    issuerMetadata = issuerMetadata,
    trustDecision = trustDecision,
    policyDecision = policyDecision,
    preparedAuthorization = preparedAuthorization,
    authorizedContext = authorizedContext,
    deferredHandle = deferredHandle,
    issuedCredentials = issuedCredentials,
    notificationOutcome = notificationOutcome,
    error = error,
)

fun IssuanceSession.toContext(): IssuanceContext = IssuanceContext(
    sessionMeta = sessionMeta,
    state = state,
    flow = flow,
    offerUri = offerUri,
    credentialIssuerId = credentialIssuerId,
    credentialConfigurationIds = credentialConfigurationIds,
    resolvedOffer = resolvedOffer,
    issuerMetadata = issuerMetadata,
    trustDecision = trustDecision,
    policyDecision = policyDecision,
    preparedAuthorization = preparedAuthorization,
    authorizedContext = authorizedContext,
    deferredHandle = deferredHandle,
    issuedCredentials = issuedCredentials,
    notificationOutcome = notificationOutcome,
    error = error,
)
