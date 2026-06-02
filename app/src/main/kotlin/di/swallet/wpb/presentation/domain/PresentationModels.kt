package di.swallet.wpb.presentation.domain

import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Supported credential formats.
 */
enum class CredentialFormat {
    SD_JWT,
    MDOC,
}

/**
 * Formal lifecycle states for an OpenID4VP presentation session.
 */
enum class PresentationState {
    RECEIVED,
    REQUEST_RESOLVED,
    VERIFIER_VALIDATED,
    POLICY_EVALUATED,
    CONSENT_PENDING,
    CONSENT_GRANTED,
    VP_BUILT,
    DISPATCHED,
    FAILED,
    REJECTED,
    EXPIRED,
    ;

    val isTerminal: Boolean
        get() = this in setOf(FAILED, REJECTED, DISPATCHED, EXPIRED)

    fun canTransitionTo(next: PresentationState): Boolean = when (this) {
        RECEIVED -> next in setOf(REQUEST_RESOLVED, FAILED, REJECTED, DISPATCHED, EXPIRED)
        REQUEST_RESOLVED -> next in setOf(VERIFIER_VALIDATED, FAILED, REJECTED, DISPATCHED, EXPIRED)
        VERIFIER_VALIDATED -> next in setOf(POLICY_EVALUATED, FAILED, REJECTED, DISPATCHED, EXPIRED)
        POLICY_EVALUATED -> next in setOf(CONSENT_PENDING, FAILED, REJECTED, DISPATCHED, EXPIRED)
        CONSENT_PENDING -> next in setOf(CONSENT_GRANTED, REJECTED, FAILED, DISPATCHED, EXPIRED)
        CONSENT_GRANTED -> next in setOf(VP_BUILT, FAILED, DISPATCHED, EXPIRED)
        VP_BUILT -> next in setOf(DISPATCHED, FAILED, EXPIRED)
        REJECTED -> next in setOf(DISPATCHED, FAILED, EXPIRED)
        DISPATCHED -> next == EXPIRED
        FAILED -> next in setOf(DISPATCHED, EXPIRED)
        EXPIRED -> next == EXPIRED
    }
}

/**
 * Metadata that scopes a single presentation lifecycle.
 *
 * `correlationId` is a stable, log-safe identifier used for tracing the full
 * request/response cycle across logs, the event store, and the verifier
 * emulator (`X-Correlation-Id` header).
 */
data class SessionMetadata(
    val sessionId: UUID,
    val holderId: String? = null,
    val correlationId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val version: Long = 0,
)

/**
 * Canonical verifier identity resolved for a presentation request.
 */
data class VerifierIdentity(
    val clientId: String,
    val displayName: String? = null,
    val clientIdPrefix: String? = null,
)

/**
 * A single DCQL-style credential query carried through the lifecycle.
 *
 * `requestedClaims` is a flat list of top-level claim names that the verifier
 * asked for. For HAIP Phase 1 (SD-JWT only) we treat the DCQL `claims[].path`
 * array as a single-element top-level claim name.
 */
data class CredentialQuery(
    val id: String,
    val format: CredentialFormat,
    val credentialTypeHints: List<String> = emptyList(),
    val requestedClaims: List<String> = emptyList(),
)

/**
 * Parsed requirements extracted from the verifier request.
 *
 * `credentialQueries` is the structured representation, while `credentialQueryIds`
 * is kept for backward compatibility with earlier orchestrator code.
 */
data class PresentationRequirements(
    val dcqlQueryJson: String,
    val credentialQueryIds: List<String>,
    val requestedFormats: Set<CredentialFormat> = setOf(CredentialFormat.SD_JWT),
    val credentialQueries: List<CredentialQuery> = emptyList(),
)

/**
 * Result of trust validation for the verifier.
 */
enum class TrustDecisionMode {
    TRUSTED,
    DEGRADED_DEMO_OPEN,
    REJECTED,
}

data class TrustDecision(
    val trusted: Boolean,
    val mode: TrustDecisionMode = if (trusted) TrustDecisionMode.TRUSTED else TrustDecisionMode.REJECTED,
    val reason: String? = null,
)

/**
 * Result of wallet policy evaluation.
 */
data class PolicyDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

/**
 * Holder consent decision.
 */
data class ConsentDecision(
    val granted: Boolean,
    val reason: String? = null,
    val selectedCredentialIds: List<String> = emptyList(),
)

/**
 * Minimal candidate record that the matcher exposes to the orchestrator.
 *
 * `requestedClaims` carries the claim names that the verifier asked for via
 * DCQL, so the VP builder can filter selective disclosures accordingly.
 */
data class CredentialCandidate(
    val candidateId: String,
    val credentialId: Long?,
    val holderId: String,
    val queryId: String,
    val credentialType: String,
    val format: CredentialFormat,
    val requestedClaims: List<String> = emptyList(),
)

/**
 * Selected credentials after consent.
 */
data class SelectedCredential(
    val candidateId: String,
    val credentialId: Long?,
    val holderId: String,
    val queryId: String,
    val credentialType: String,
    val format: CredentialFormat,
    val requestedClaims: List<String> = emptyList(),
)

/**
 * Minimal VP token abstraction used by the orchestrator.
 */
data class VpToken(
    val presentationsByQueryId: Map<String, List<String>>,
    val format: CredentialFormat = CredentialFormat.SD_JWT,
    val rawValue: String? = null,
)

/**
 * Internal presentation error used by the lifecycle.
 */
data class PresentationError(
    val errorToken: String? = null,
    val code: String,
    val message: String,
)

/**
 * Domain-level dispatch outcome mirrored from the SDK boundary.
 */
sealed interface PresentationDispatchOutcome {
    data class RedirectUri(val value: URI) : PresentationDispatchOutcome
    data class VerifierAccepted(val redirectUri: URI?) : PresentationDispatchOutcome
    data object VerifierRejected : PresentationDispatchOutcome
}

/**
 * Runtime state container for an OpenID4VP transaction.
 */
data class PresentationContext(
    val sessionMeta: SessionMetadata,
    val state: PresentationState,
    val authorizationRequest: ResolvedAuthorizationRequest? = null,
    val verifierIdentity: VerifierIdentity? = null,
    val trustDecision: TrustDecision? = null,
    val registryDecision: RegistryDecision? = null,
    val registryRecord: RpRegistryRecord? = null,
    val presentationRequirements: PresentationRequirements? = null,
    val credentialCandidates: List<CredentialCandidate> = emptyList(),
    val policyDecision: PolicyDecision? = null,
    val consentDecision: ConsentDecision? = null,
    val selectedCredentials: List<SelectedCredential> = emptyList(),
    val vpToken: VpToken? = null,
    val dispatchOutcome: PresentationDispatchOutcome? = null,
    val error: PresentationError? = null,
)

/**
 * Persisted representation of a presentation transaction.
 */
data class PresentationSession(
    val sessionMeta: SessionMetadata,
    val state: PresentationState,
    val authorizationRequest: ResolvedAuthorizationRequest? = null,
    val verifierIdentity: VerifierIdentity? = null,
    val trustDecision: TrustDecision? = null,
    val registryDecision: RegistryDecision? = null,
    val registryRecord: RpRegistryRecord? = null,
    val presentationRequirements: PresentationRequirements? = null,
    val credentialCandidates: List<CredentialCandidate> = emptyList(),
    val policyDecision: PolicyDecision? = null,
    val consentDecision: ConsentDecision? = null,
    val selectedCredentials: List<SelectedCredential> = emptyList(),
    val vpToken: VpToken? = null,
    val dispatchOutcome: PresentationDispatchOutcome? = null,
    val error: PresentationError? = null,
)

fun PresentationContext.toSession(): PresentationSession = PresentationSession(
    sessionMeta = sessionMeta,
    state = state,
    authorizationRequest = authorizationRequest,
    verifierIdentity = verifierIdentity,
    trustDecision = trustDecision,
    registryDecision = registryDecision,
    registryRecord = registryRecord,
    presentationRequirements = presentationRequirements,
    credentialCandidates = credentialCandidates,
    policyDecision = policyDecision,
    consentDecision = consentDecision,
    selectedCredentials = selectedCredentials,
    vpToken = vpToken,
    dispatchOutcome = dispatchOutcome,
    error = error,
)

fun PresentationSession.toContext(): PresentationContext = PresentationContext(
    sessionMeta = sessionMeta,
    state = state,
    authorizationRequest = authorizationRequest,
    verifierIdentity = verifierIdentity,
    trustDecision = trustDecision,
    registryDecision = registryDecision,
    registryRecord = registryRecord,
    presentationRequirements = presentationRequirements,
    credentialCandidates = credentialCandidates,
    policyDecision = policyDecision,
    consentDecision = consentDecision,
    selectedCredentials = selectedCredentials,
    vpToken = vpToken,
    dispatchOutcome = dispatchOutcome,
    error = error,
)
