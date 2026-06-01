package di.swallet.wpb.presentation.orchestration

import di.swallet.wpb.observability.SessionEventStore
import di.swallet.wpb.observability.SessionEvent
import di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.presentation.domain.ConsentDecision
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationError
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.presentation.domain.TrustDecisionMode
import di.swallet.wpb.presentation.domain.toContext
import di.swallet.wpb.presentation.domain.toSession
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.matching.CredentialMatcher
import di.swallet.wpb.presentation.persistence.PresentationSessionRepository
import di.swallet.wpb.presentation.policy.PolicyEngine
import di.swallet.wpb.presentation.trust.TrustValidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class DefaultPresentationFlowOrchestrator(
    private val gateway: OpenId4VpGateway,
    private val repository: PresentationSessionRepository,
    private val trustValidator: TrustValidator,
    private val policyEngine: PolicyEngine,
    private val credentialMatcher: CredentialMatcher,
    private val vpTokenBuilder: VpTokenBuilder,
    private val eventStore: SessionEventStore,
    @param:Value("\${wpb.openid4vp.session.ttl-seconds:600}") private val sessionTtlSeconds: Long = 600,
) : PresentationFlowOrchestrator {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val sessionTtl: Duration get() = Duration.ofSeconds(sessionTtlSeconds)

    override suspend fun startSession(requestUri: String, holderId: String?): PresentationContext {
        val now = Instant.now()
        val baseContext = newContext(holderId, now)
        persistNew(baseContext)
        record(baseContext, "authorize.request.received", mapOf("requestUri" to requestUri, "holder" to (holderId ?: "")))

        return when (val resolution = gateway.resolveRequestUri(requestUri)) {
            is AuthorizationRequestResolution.Invalid -> handleInvalidResolution(baseContext, resolution)
            is AuthorizationRequestResolution.Success -> handleSuccessfulResolution(baseContext, resolution)
        }
    }

    private suspend fun handleInvalidResolution(
        baseContext: PresentationContext,
        resolution: AuthorizationRequestResolution.Invalid,
    ): PresentationContext {
        val error = resolution.error
        val failed = transitionTo(
            baseContext.copy(
                error = PresentationError(
                    errorToken = error.errorToken,
                    code = error.errorCode,
                    message = error.message ?: "Authorization request rejected by the SDK",
                ),
            ),
            PresentationState.FAILED,
        )
        val persistedFailed = persistUpdate(failed)
        record(persistedFailed, "authorize.request.invalid", mapOf("code" to (persistedFailed.error?.code ?: "unknown")))

        return if (error.dispatchDetails != null) {
            val dispatchOutcome = gateway.dispatchError(error.errorToken)
            val dispatched = transitionTo(
                persistedFailed.copy(dispatchOutcome = dispatchOutcome),
                PresentationState.DISPATCHED,
                allowFromFailed = true,
            )
            val after = persistUpdate(dispatched)
            record(after, "authorize.dispatch.error", mapOf("outcome" to dispatchOutcome.toString()))
            after
        } else {
            persistedFailed
        }
    }

    private suspend fun handleSuccessfulResolution(
        baseContext: PresentationContext,
        resolution: AuthorizationRequestResolution.Success,
    ): PresentationContext {
        var context = transitionTo(
            baseContext.copy(
                authorizationRequest = resolution.request,
                presentationRequirements = resolution.request.requirements,
            ),
            PresentationState.REQUEST_RESOLVED,
        )
        context = persistUpdate(context)
        record(context, "authorize.request.resolved", mapOf("requestToken" to (context.authorizationRequest?.requestToken ?: "")))

        val trustEvaluated = trustValidator.validate(context)
        val trusted = trustEvaluated.trustDecision?.trusted == true
        context = if (trusted) {
            transitionTo(trustEvaluated, PresentationState.VERIFIER_VALIDATED)
        } else {
            transitionTo(
                trustEvaluated.copy(
                    error = PresentationError(
                        code = "trust_rejected",
                        message = trustEvaluated.trustDecision?.reason ?: "Verifier failed trust validation",
                    ),
                ),
                PresentationState.REJECTED,
            )
        }
        context = persistUpdate(context)
        record(
            context,
            "trust.validated",
            mapOf(
                "trusted" to trusted.toString(),
                "mode" to (trustEvaluated.trustDecision?.mode?.name ?: ""),
                "reason" to (trustEvaluated.trustDecision?.reason ?: ""),
            ),
        )
        when (trustEvaluated.trustDecision?.mode) {
            TrustDecisionMode.TRUSTED -> {
                record(context, "trust.validation.passed", mapOf("reason" to (trustEvaluated.trustDecision?.reason ?: "")))
            }
            TrustDecisionMode.DEGRADED_DEMO_OPEN -> {
                record(context, "trust.validation.degraded", mapOf("reason" to (trustEvaluated.trustDecision?.reason ?: "")))
            }
            else -> {
                record(context, "trust.validation.failed", mapOf("reason" to (trustEvaluated.trustDecision?.reason ?: "")))
            }
        }

        if (!trusted) {
            return dispatchTerminalNegative(context, "trust.dispatch.negative")
        }

        context = credentialMatcher.match(context)
        context = persistUpdate(context)
        record(context, "matching.completed", mapOf("candidates" to context.credentialCandidates.size.toString()))

        context = policyEngine.evaluate(context)
        val policyAllowed = context.policyDecision?.allowed == true

        context = if (policyAllowed) {
            transitionTo(context, PresentationState.POLICY_EVALUATED)
        } else {
            transitionTo(
                context.copy(
                    error = PresentationError(
                        code = "policy_rejected",
                        message = context.policyDecision?.reason ?: "Policy denied the request",
                    ),
                ),
                PresentationState.REJECTED,
            )
        }
        context = persistUpdate(context)
        record(context, "policy.evaluated", mapOf("allowed" to policyAllowed.toString()))

        if (!policyAllowed) {
            return dispatchTerminalNegative(context, "policy.dispatch.negative")
        }

        if (context.credentialCandidates.isEmpty()) {
            val rejected = transitionTo(
                context.copy(
                    error = PresentationError(
                        code = "no_matching_credentials",
                        message = "No credentials matched the verifier request",
                    ),
                ),
                PresentationState.REJECTED,
            )
            val persisted = persistUpdate(rejected)
            record(persisted, "matching.empty", emptyMap())
            return dispatchTerminalNegative(persisted, "matching.dispatch.negative")
        }

        context = transitionTo(context, PresentationState.CONSENT_PENDING)
        context = persistUpdate(context)
        record(context, "consent.pending", mapOf("candidates" to context.credentialCandidates.size.toString()))
        return context
    }

    override suspend fun submitConsent(sessionId: UUID, decision: ConsentSubmission): PresentationContext {
        val current = getSession(sessionId)
        if (current.state == PresentationState.EXPIRED) {
            throw ResponseStatusException(HttpStatus.GONE, "Session $sessionId has expired")
        }
        if (current.state != PresentationState.CONSENT_PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Session $sessionId is not awaiting consent (state=${current.state})")
        }

        return if (!decision.granted) {
            handleConsentDenied(current, decision)
        } else {
            handleConsentGranted(current, decision)
        }
    }

    private suspend fun handleConsentDenied(current: PresentationContext, decision: ConsentSubmission): PresentationContext {
        val rejected = transitionTo(
            current.copy(
                consentDecision = ConsentDecision(
                    granted = false,
                    reason = decision.reason ?: "Holder rejected the request",
                    selectedCredentialIds = emptyList(),
                ),
            ),
            PresentationState.REJECTED,
        )
        val persistedRejected = persistUpdate(rejected)
        record(persistedRejected, "consent.rejected", mapOf("reason" to (decision.reason ?: "")))
        return dispatchTerminalNegative(persistedRejected, "dispatch.negative")
    }

    private suspend fun handleConsentGranted(current: PresentationContext, decision: ConsentSubmission): PresentationContext {
        val selected = selectCredentials(current, decision.selectedCredentialIds)
        if (selected.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No valid credentials were selected for session ${current.sessionMeta.sessionId}")
        }
        var context = transitionTo(
            current.copy(
                consentDecision = ConsentDecision(
                    granted = true,
                    reason = decision.reason,
                    selectedCredentialIds = selected.map { it.candidateId },
                ),
                selectedCredentials = selected,
            ),
            PresentationState.CONSENT_GRANTED,
        )
        context = persistUpdate(context)
        record(context, "consent.granted", mapOf("selected" to selected.size.toString()))

        context = transitionTo(vpTokenBuilder.build(context), PresentationState.VP_BUILT)
        context = persistUpdate(context)
        record(context, "vp.built", mapOf("format" to (context.vpToken?.format?.name ?: "")))

        val requestToken = checkNotNull(context.authorizationRequest).requestToken
        val dispatchOutcome = gateway.dispatchPositive(requestToken, checkNotNull(context.vpToken))
        val dispatched = transitionTo(context.copy(dispatchOutcome = dispatchOutcome), PresentationState.DISPATCHED)
        val after = persistUpdate(dispatched)
        record(after, "dispatch.positive", mapOf("outcome" to dispatchOutcome.toString()))
        return after
    }

    override suspend fun getSession(sessionId: UUID): PresentationContext {
        val session = repository.findById(sessionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "PresentationSession $sessionId not found")
        val now = Instant.now()
        val terminal = setOf(
            PresentationState.DISPATCHED,
            PresentationState.EXPIRED,
            PresentationState.FAILED,
            PresentationState.REJECTED,
        )
        if (!now.isAfter(session.sessionMeta.expiresAt) || session.state in terminal) {
            return session.toContext()
        }
        val expired = transitionTo(session.toContext(), PresentationState.EXPIRED)
        val persisted = persistUpdate(expired)
        record(persisted, "session.expired", emptyMap())
        return persisted
    }

    private suspend fun dispatchTerminalNegative(context: PresentationContext, eventType: String): PresentationContext {
        val requestToken = context.authorizationRequest?.requestToken
            ?: return context
        val outcome = gateway.dispatchNegative(requestToken)
        val dispatched = transitionTo(
            context.copy(dispatchOutcome = outcome),
            PresentationState.DISPATCHED,
            allowFromRejected = true,
        )
        val after = persistUpdate(dispatched)
        record(after, eventType, mapOf("outcome" to outcome.toString()))
        return after
    }

    private fun transitionTo(
        context: PresentationContext,
        next: PresentationState,
        allowFromFailed: Boolean = false,
        allowFromRejected: Boolean = false,
    ): PresentationContext {
        val from = context.state
        if (from == next) return context

        // Whitelisted controlled bridges (FAILED -> DISPATCHED, REJECTED -> DISPATCHED)
        val explicitlyAllowed =
            (allowFromFailed && from == PresentationState.FAILED && next == PresentationState.DISPATCHED) ||
                (allowFromRejected && from == PresentationState.REJECTED && next == PresentationState.DISPATCHED)

        if (!explicitlyAllowed && !from.canTransitionTo(next)) {
            logger.error("Illegal lifecycle transition: {} -> {} on session {}", from, next, context.sessionMeta.sessionId)
            throw IllegalStateException("Illegal presentation lifecycle transition $from -> $next")
        }
        return context.copy(state = next)
    }

    private fun newContext(holderId: String?, now: Instant): PresentationContext {
        val sessionId = UUID.randomUUID()
        val meta = SessionMetadata(
            sessionId = sessionId,
            holderId = holderId,
            correlationId = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plus(sessionTtl),
            version = 0,
        )
        return PresentationContext(
            sessionMeta = meta,
            state = PresentationState.RECEIVED,
        )
    }

    private fun persistNew(context: PresentationContext): PresentationContext {
        return repository.create(context.toSession()).toContext()
    }

    private fun persistUpdate(context: PresentationContext): PresentationContext {
        return repository.update(context.toSession()).toContext()
    }

    private fun record(context: PresentationContext, type: String, attributes: Map<String, String>) {
        val event = SessionEvent(
            sessionId = context.sessionMeta.sessionId,
            correlationId = context.sessionMeta.correlationId,
            timestamp = Instant.now(),
            type = type,
            state = context.state,
            attributes = attributes,
        )
        eventStore.record(event)
        logger.info(
            "lifecycle session={} corr={} state={} event={} attrs={}",
            context.sessionMeta.sessionId,
            context.sessionMeta.correlationId,
            context.state,
            type,
            attributes,
        )
    }

    private fun selectCredentials(
        context: PresentationContext,
        selectedCredentialIds: List<String>,
    ): List<SelectedCredential> {
        val candidatesById = context.credentialCandidates.associateBy { it.candidateId }
        val selectedCandidates = if (selectedCredentialIds.isEmpty()) {
            // Default: select one candidate per queryId so the verifier always gets a complete response.
            context.credentialCandidates.groupBy { it.queryId }.map { it.value.first() }
        } else {
            selectedCredentialIds.mapNotNull { candidatesById[it] }
        }

        return selectedCandidates.map {
            SelectedCredential(
                candidateId = it.candidateId,
                credentialId = it.credentialId,
                holderId = it.holderId,
                queryId = it.queryId,
                credentialType = it.credentialType,
                format = it.format,
                requestedClaims = it.requestedClaims,
            )
        }
    }
}
