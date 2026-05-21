package di.swallet.wpb.presentation.orchestration

import di.swallet.wpb.openid4vp.adapter.OpenId4VpGateway
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.ConsentSubmission
import di.swallet.wpb.presentation.domain.ConsentDecision
import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.PresentationError
import di.swallet.wpb.presentation.domain.PresentationSession
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.presentation.domain.toContext
import di.swallet.wpb.presentation.domain.toSession
import di.swallet.wpb.presentation.format.VpTokenBuilder
import di.swallet.wpb.presentation.matching.CredentialMatcher
import di.swallet.wpb.presentation.persistence.PresentationSessionRepository
import di.swallet.wpb.presentation.policy.PolicyEngine
import di.swallet.wpb.presentation.trust.TrustValidator
import org.springframework.stereotype.Service
import org.springframework.http.HttpStatus
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
    private val eventStore: di.swallet.wpb.observability.SessionEventStore,
) : PresentationFlowOrchestrator {

    private val sessionTtl: Duration = Duration.ofMinutes(10)

    override suspend fun startSession(requestUri: String, holderId: String?): PresentationContext {
        val now = Instant.now()
        val baseContext = newContext(holderId, now)
        persistNew(baseContext)
        eventStore.record(baseContext.sessionMeta.sessionId, "authorize.request.received: requestUri=$requestUri holder=$holderId")

        return when (val resolution = gateway.resolveRequestUri(requestUri)) {
            is AuthorizationRequestResolution.Invalid -> {
                val error = resolution.error
                val failed = baseContext.copy(
                    state = PresentationState.FAILED,
                    error = PresentationError(
                        errorToken = error.errorToken,
                        code = error.errorCode,
                        message = error.message ?: "Authorization request rejected by the SDK",
                    ),
                )
                val persistedFailed = persistUpdate(failed)
                eventStore.record(persistedFailed.sessionMeta.sessionId, "authorize.request.invalid: ${failed.error?.code ?: failed.error}")

                if (error.dispatchDetails != null) {
                    val dispatched = persistedFailed.copy(
                        dispatchOutcome = gateway.dispatchError(error.errorToken),
                        state = PresentationState.DISPATCHED,
                    )
                    val after = persistUpdate(dispatched)
                    eventStore.record(after.sessionMeta.sessionId, "authorize.dispatch.error: outcome=${after.dispatchOutcome}")
                    after
                } else {
                    persistedFailed
                }
            }

            is AuthorizationRequestResolution.Success -> {
                var context = baseContext.copy(
                    state = PresentationState.REQUEST_RESOLVED,
                    authorizationRequest = resolution.request,
                    presentationRequirements = resolution.request.requirements,
                )
                context = persistUpdate(context)
                eventStore.record(context.sessionMeta.sessionId, "authorize.request.resolved: requestToken=${context.authorizationRequest?.requestToken}")

                context = trustValidator.validate(context).copy(state = PresentationState.VERIFIER_VALIDATED)
                context = persistUpdate(context)
                eventStore.record(context.sessionMeta.sessionId, "trust.validated: trusted=${context.trustDecision?.trusted}")

                context = policyEngine.evaluate(context).copy(
                    state = if (context.policyDecision?.allowed == true) {
                        PresentationState.POLICY_EVALUATED
                    } else {
                        PresentationState.REJECTED
                    },
                    error = if (context.policyDecision?.allowed == true) {
                        context.error
                    } else {
                        PresentationError(
                            code = "policy_rejected",
                            message = context.policyDecision?.reason ?: "Policy denied the request",
                        )
                    },
                )
                context = persistUpdate(context)
                eventStore.record(context.sessionMeta.sessionId, "policy.evaluated: allowed=${context.policyDecision?.allowed}")

                if (context.policyDecision?.allowed != true) {
                    return context
                }

                context = credentialMatcher.match(context).copy(state = PresentationState.CONSENT_PENDING)
                context = persistUpdate(context)
                eventStore.record(context.sessionMeta.sessionId, "matching.completed: candidates=${context.credentialCandidates.size}")

                if (context.credentialCandidates.isEmpty()) {
                    val rejected = context.copy(
                        state = PresentationState.REJECTED,
                        error = PresentationError(
                            code = "no_matching_credentials",
                            message = "No credentials matched the request",
                        ),
                    )
                    return persistUpdate(rejected)
                }

                context
            }
        }
    }

    override suspend fun submitConsent(sessionId: UUID, decision: ConsentSubmission): PresentationContext {
        val current = getSession(sessionId)
        require(current.state == PresentationState.CONSENT_PENDING) {
            "Session $sessionId is not awaiting consent"
        }

        val updated = if (!decision.granted) {
            val rejected = current.copy(
                consentDecision = ConsentDecision(
                    granted = false,
                    reason = decision.reason ?: "Holder rejected the request",
                    selectedCredentialIds = emptyList(),
                ),
                state = PresentationState.REJECTED,
            )
            val persistedRejected = persistUpdate(rejected)
            eventStore.record(persistedRejected.sessionMeta.sessionId, "consent.rejected")
            val dispatched = persistedRejected.copy(
                dispatchOutcome = gateway.dispatchNegative(checkNotNull(persistedRejected.authorizationRequest).requestToken),
                state = PresentationState.DISPATCHED,
            )
            val after = persistUpdate(dispatched)
            eventStore.record(after.sessionMeta.sessionId, "dispatch.negative: outcome=${after.dispatchOutcome}")
            after
        } else {
            val selected = selectCredentials(current, decision.selectedCredentialIds)
            require(selected.isNotEmpty()) {
                "No valid credentials were selected for session $sessionId"
            }
            val consentGranted = current.copy(
                consentDecision = ConsentDecision(
                    granted = true,
                    reason = decision.reason,
                    selectedCredentialIds = selected.map { it.candidateId },
                ),
                selectedCredentials = selected,
                state = PresentationState.CONSENT_GRANTED,
            )
            var context = persistUpdate(consentGranted)
            eventStore.record(context.sessionMeta.sessionId, "consent.granted: selected=${context.selectedCredentials.size}")

            context = vpTokenBuilder.build(context).copy(state = PresentationState.VP_BUILT)
            context = persistUpdate(context)
            eventStore.record(context.sessionMeta.sessionId, "vp.built: format=${context.vpToken?.format}")

            val requestToken = checkNotNull(context.authorizationRequest).requestToken
            val dispatched = context.copy(
                dispatchOutcome = gateway.dispatchPositive(requestToken, checkNotNull(context.vpToken)),
                state = PresentationState.DISPATCHED,
            )
            val after = persistUpdate(dispatched)
            eventStore.record(after.sessionMeta.sessionId, "dispatch.positive: outcome=${after.dispatchOutcome}")
            after
        }

        return updated
    }

    override suspend fun getSession(sessionId: UUID): PresentationContext {
        val session = repository.findById(sessionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "PresentationSession $sessionId not found")
        val now = Instant.now()
        val expired = now.isAfter(session.sessionMeta.expiresAt) && session.state !in setOf(PresentationState.DISPATCHED, PresentationState.EXPIRED)
        val current = if (expired) {
            persistUpdate(
                session.toContext().copy(
                    state = PresentationState.EXPIRED,
                ),
            )
        } else {
            session.toContext()
        }
        return current
    }

    private fun newContext(holderId: String?, now: Instant): PresentationContext {
        val sessionId = UUID.randomUUID()
        val meta = SessionMetadata(
            sessionId = sessionId,
            holderId = holderId,
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

    private fun selectCredentials(
        context: PresentationContext,
        selectedCredentialIds: List<String>,
    ): List<SelectedCredential> {
        val candidatesById = context.credentialCandidates.associateBy { it.candidateId }
        val selectedCandidates = if (selectedCredentialIds.isEmpty()) {
            context.credentialCandidates.take(1)
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
            )
        }
    }
}
