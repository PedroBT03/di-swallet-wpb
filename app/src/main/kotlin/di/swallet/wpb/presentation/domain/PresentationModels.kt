/**
 * Domain models for the OpenID4VP presentation session lifecycle.
 */

package di.swallet.wpb.presentation.domain

import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Credential encoding formats supported during presentation. */
enum class CredentialFormat {
    SD_JWT,
    MDOC,
}

/** Lifecycle states for an OpenID4VP presentation session. */
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

    /** Returns whether the session may move from this state to [next]. */
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
 * Identifiers and timestamps that scope one presentation session.
 * [correlationId] is shared with logs, the event store, and the verifier emulator.
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

/** Verifier identity resolved from the authorization request. */
data class VerifierIdentity(
    val clientId: String,
    val displayName: String? = null,
    val clientIdPrefix: String? = null,
)

/**
 * One DCQL credential query carried through the presentation lifecycle.
 * [requestedClaimPaths] preserves the full DCQL claim path for each requested claim.
 */
data class CredentialQuery(
    val id: String,
    val format: CredentialFormat,
    val credentialTypeHints: List<String> = emptyList(),
    val requestedClaimPaths: List<ClaimPath> = emptyList(),
) {
    /** Dot-notation claim names used by registry matching and mdoc filters. */
    val requestedClaims: List<String>
        get() = requestedClaimPaths.map { it.toDotNotation() }
}

/**
 * Parsed verifier requirements extracted from the authorization request.
 * [credentialQueryIds] remains for compatibility with older orchestrator code.
 */
data class PresentationRequirements(
    val dcqlQueryJson: String,
    val credentialQueryIds: List<String>,
    val requestedFormats: Set<CredentialFormat> = setOf(CredentialFormat.SD_JWT),
    val credentialQueries: List<CredentialQuery> = emptyList(),
)

/** How trust validation classified the verifier. */
enum class TrustDecisionMode {
    TRUSTED,
    DEGRADED_DEMO_OPEN,
    REJECTED,
}

/** Result of validating the verifier against trust anchors and allow-lists. */
data class TrustDecision(
    val trusted: Boolean,
    val mode: TrustDecisionMode = if (trusted) TrustDecisionMode.TRUSTED else TrustDecisionMode.REJECTED,
    val reason: String? = null,
)

/** Result of wallet policy evaluation for the presentation request. */
data class PolicyDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

/** Holder consent outcome, including selected credential candidates. */
data class ConsentDecision(
    val granted: Boolean,
    val reason: String? = null,
    val selectedCredentialIds: List<String> = emptyList(),
)

/**
 * Credential that matched a verifier query and may be shown for consent.
 * [requestedClaimPaths] drives SD-JWT selective disclosure in the VP builder.
 */
data class CredentialCandidate(
    val candidateId: String,
    val credentialId: Long?,
    val holderId: String,
    val queryId: String,
    val credentialType: String,
    val format: CredentialFormat,
    val requestedClaimPaths: List<ClaimPath> = emptyList(),
) {
    val requestedClaims: List<String>
        get() = requestedClaimPaths.map { it.toDotNotation() }
}

/** Credentials the holder chose to present after granting consent. */
data class SelectedCredential(
    val candidateId: String,
    val credentialId: Long?,
    val holderId: String,
    val queryId: String,
    val credentialType: String,
    val format: CredentialFormat,
    val requestedClaimPaths: List<ClaimPath> = emptyList(),
) {
    val requestedClaims: List<String>
        get() = requestedClaimPaths.map { it.toDotNotation() }
}

/** VP token payload keyed by DCQL query id before adapter dispatch. */
data class VpToken(
    val presentationsByQueryId: Map<String, List<String>>,
    val format: CredentialFormat = CredentialFormat.SD_JWT,
    val rawValue: String? = null,
)

/** Internal presentation error attached when the lifecycle fails or is rejected. */
data class PresentationError(
    val errorToken: String? = null,
    val code: String,
    val message: String,
)

/** Outcome of sending the presentation response back to the verifier. */
sealed interface PresentationDispatchOutcome {
    /** Browser redirect URI returned by the verifier or adapter. */
    data class RedirectUri(val value: URI) : PresentationDispatchOutcome

    /** Verifier accepted the response, optionally with a follow-up redirect. */
    data class VerifierAccepted(val redirectUri: URI?) : PresentationDispatchOutcome

    /** Verifier rejected the response. */
    data object VerifierRejected : PresentationDispatchOutcome
}

/** In-memory working state for an active presentation session. */
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

/** Persisted snapshot of a presentation session. */
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

/** Converts runtime context into a persistable session snapshot. */
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

/** Rehydrates a persisted session into runtime context. */
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
