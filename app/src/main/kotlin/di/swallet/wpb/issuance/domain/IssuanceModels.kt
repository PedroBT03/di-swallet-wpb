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
 * `SD_JWT_VC` and `MSO_MDOC` are both supported in runtime issuance paths.
 */
enum class IssuanceCredentialFormat {
    SD_JWT_VC,
    MSO_MDOC,
    UNKNOWN,
}

/**
 * Lifecycle of the WIA sub-context embedded in issuance.
 *
 * WIA is intentionally not modeled as a top-level issuance lifecycle. It is a
 * mandatory sub-flow that can be regenerated/re-attached as retries happen.
 */
enum class WiaState {
    REQUIRED,
    SELECTED,
    ATTACHED,
    VALIDATED,
    EXPIRED,
    FAILED,
}

/**
 * Lifecycle of the KA sub-context embedded in issuance.
 */
enum class KaState {
    NOT_REQUIRED,
    REQUIRED,
    SELECTED,
    ATTACHED,
    VALIDATED,
    EXPIRED,
    FAILED,
}

data class WiaStatusReference(
    val listId: String,
    val index: Int,
    val uri: String,
)

data class KaStatusReference(
    val listId: String,
    val index: Int,
    val uri: String,
)

/**
 * Wallet Instance Attestation envelope used by the wallet-side issuance flow.
 */
data class WalletInstanceAttestation(
    val jwt: String,
    val popJwt: String,
    val walletInstanceId: String,
    val walletName: String,
    val walletVersion: String,
    val walletLink: String? = null,
    val walletSolutionCertificationInformation: String,
    val cnfJkt: String,
    val clientStatus: WiaStatusReference,
    val tokenExpiresAt: Instant,
    val clientStatusExpiresAt: Instant,
    val issuedAt: Instant,
    val issuerScope: String? = null,
)

data class WiaContext(
    val state: WiaState = WiaState.REQUIRED,
    val attestation: WalletInstanceAttestation? = null,
    val nonceMismatchRetries: Int = 0,
    val expiredRetries: Int = 0,
    val lastErrorCode: String? = null,
)

/**
 * Wallet Key Attestation envelope used by device-bound issuance.
 */
data class KeyAttestation(
    val jwt: String,
    val keyId: String,
    val keyStorage: String,
    val certification: String,
    val attestedJkt: String,
    val status: KaStatusReference,
    val tokenExpiresAt: Instant,
    val statusExpiresAt: Instant,
    val issuedAt: Instant,
    val issuerScope: String? = null,
    val x5c: List<String> = emptyList(),
)

data class KaContext(
    val state: KaState = KaState.NOT_REQUIRED,
    val attestation: KeyAttestation? = null,
    val lastErrorCode: String? = null,
)

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
    val wia: WiaContext? = null,
    val ka: KaContext? = null,
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
    val wia: WiaContext? = null,
    val ka: KaContext? = null,
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
    wia = wia,
    ka = ka,
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
    wia = wia,
    ka = ka,
    deferredHandle = deferredHandle,
    issuedCredentials = issuedCredentials,
    notificationOutcome = notificationOutcome,
    error = error,
)
