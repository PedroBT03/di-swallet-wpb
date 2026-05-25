package di.swallet.wpb.issuance.orchestration

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceError
import di.swallet.wpb.issuance.domain.IssuanceSessionMetadata
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.domain.toContext
import di.swallet.wpb.issuance.domain.toSession
import di.swallet.wpb.issuance.persistence.IssuanceSessionRepository
import di.swallet.wpb.issuance.policy.IssuancePolicy
import di.swallet.wpb.issuance.proof.ProofMaterialProvider
import di.swallet.wpb.issuance.storage.IssuedCredentialStorage
import di.swallet.wpb.issuance.trust.IssuerTrustValidator
import di.swallet.wpb.observability.IssuanceEvent
import di.swallet.wpb.observability.IssuanceEventStore
import di.swallet.wpb.openid4vci.adapter.OpenId4VciGateway
import di.swallet.wpb.openid4vci.protocol.AuthorizationFlowKind
import di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class DefaultIssuanceFlowOrchestrator(
    private val gateway: OpenId4VciGateway,
    private val repository: IssuanceSessionRepository,
    private val trustValidator: IssuerTrustValidator,
    private val policy: IssuancePolicy,
    private val proofProvider: ProofMaterialProvider,
    private val credentialStorage: IssuedCredentialStorage,
    private val eventStore: IssuanceEventStore,
    private val properties: OpenId4VciProperties,
) : IssuanceFlowOrchestrator {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val sessionTtl: Duration get() = Duration.ofSeconds(properties.sessionTtlSeconds)

    override fun resolveOffer(offerUri: String, holderId: String?): IssuanceContext {
        val now = Instant.now()
        val base = newContext(holderId, now).copy(offerUri = offerUri)
        persistNew(base)
        record(base, "offer.received", mapOf("offerUri" to offerUri, "holder" to (holderId ?: "")))

        val (offer, metadata) = try {
            gateway.resolveOffer(offerUri)
        } catch (ex: Exception) {
            return fail(base, IssuanceError("offer_resolution_failed", ex.message ?: "offer resolution error"))
        }

        val resolved = base.copy(
            offerUri = offerUri,
            flow = offer.authorizationFlow,
            resolvedOffer = offer,
            issuerMetadata = metadata,
            credentialIssuerId = offer.credentialIssuerId,
            credentialConfigurationIds = offer.credentialConfigurationIds,
        )
        val resolvedSaved = transitionAndPersist(resolved, IssuanceState.OFFER_RESOLVED)
        record(
            resolvedSaved,
            "offer.resolved",
            mapOf(
                "issuer" to offer.credentialIssuerId,
                "flow" to offer.authorizationFlow.name,
                "configurations" to offer.credentialConfigurationIds.joinToString(),
            ),
        )

        val trust = trustValidator.validate(metadata)
        val trustChecked = resolvedSaved.copy(trustDecision = trust)
        if (!trust.trusted) {
            val rejected = fail(trustChecked, IssuanceError("issuer_untrusted", trust.reason ?: "issuer not trusted"), terminal = IssuanceState.REJECTED)
            record(rejected, "issuer.untrusted", mapOf("reason" to (trust.reason ?: "")))
            return rejected
        }

        val policyDecision = policy.evaluate(trustChecked)
        val withPolicy = trustChecked.copy(policyDecision = policyDecision)
        if (!policyDecision.allowed) {
            val rejected = fail(withPolicy, IssuanceError("policy_denied", policyDecision.reason ?: "policy denied"), terminal = IssuanceState.REJECTED)
            record(rejected, "policy.denied", mapOf("reason" to (policyDecision.reason ?: "")))
            return rejected
        }
        val persisted = persistUpdate(withPolicy)
        record(persisted, "policy.allowed", mapOf("decision" to "allowed"))
        return persisted
    }

    override fun prepareAuthorization(sessionId: UUID): IssuanceContext {
        val ctx = loadActive(sessionId)
        require(ctx.state == IssuanceState.OFFER_RESOLVED) {
            "session $sessionId is not in OFFER_RESOLVED state (current=${ctx.state})"
        }
        val offer = ctx.resolvedOffer ?: throwBadRequest("offer not resolved")
        val metadata = ctx.issuerMetadata ?: throwBadRequest("issuer metadata not resolved")
        if (offer.authorizationFlow != AuthorizationFlowKind.AUTHORIZATION_CODE) {
            return fail(ctx, IssuanceError("invalid_flow", "offer is not authorization_code"))
        }

        val proof = proofProvider.provide(ctx.sessionMeta.holderId ?: "anonymous", metadata)
        val prepared = try {
            gateway.prepareAuthorization(
                adapterSessionId = ctx.sessionMeta.sessionId.toString(),
                offer = offer,
                metadata = metadata,
                proof = proof,
            )
        } catch (ex: Exception) {
            return fail(ctx, IssuanceError("authorization_prepare_failed", ex.message ?: "prepare failed"))
        }

        val withAuth = ctx.copy(preparedAuthorization = prepared)
        val saved = transitionAndPersist(withAuth, IssuanceState.AUTHORIZATION_PREPARED)
        record(
            saved,
            "authorization.prepared",
            mapOf(
                "pkce" to prepared.pkceUsed.toString(),
                "par" to prepared.parUsed.toString(),
                "dpop" to prepared.dpopRequested.toString(),
            ),
        )
        return saved
    }

    override fun completeAuthorizationCode(
        sessionId: UUID,
        authorizationCode: String,
        state: String,
    ): IssuanceContext {
        val ctx = loadActive(sessionId)
        require(ctx.state == IssuanceState.AUTHORIZATION_PREPARED) {
            "session $sessionId not in AUTHORIZATION_PREPARED (current=${ctx.state})"
        }
        val authorized = try {
            gateway.authorizeWithCode(
                adapterSessionId = ctx.sessionMeta.sessionId.toString(),
                authorizationCode = authorizationCode,
                state = state,
            )
        } catch (ex: Exception) {
            return fail(ctx, IssuanceError("authorization_failed", ex.message ?: "authorization failed"))
        }
        val withAuth = ctx.copy(authorizedContext = authorized)
        val saved = transitionAndPersist(withAuth, IssuanceState.AUTHORIZED)
        record(
            saved,
            "authorization.completed",
            mapOf(
                "accessToken" to authorized.accessTokenPresent.toString(),
                "refreshToken" to authorized.refreshTokenPresent.toString(),
                "dpop" to authorized.dpopUsed.toString(),
            ),
        )
        return saved
    }

    override fun completePreAuthorizedCode(sessionId: UUID, txCode: String?): IssuanceContext {
        val ctx = loadActive(sessionId)
        require(ctx.state == IssuanceState.OFFER_RESOLVED) {
            "session $sessionId not in OFFER_RESOLVED (current=${ctx.state})"
        }
        val offer = ctx.resolvedOffer ?: throwBadRequest("offer not resolved")
        val metadata = ctx.issuerMetadata ?: throwBadRequest("issuer metadata not resolved")
        if (offer.authorizationFlow != AuthorizationFlowKind.PRE_AUTHORIZED_CODE) {
            return fail(ctx, IssuanceError("invalid_flow", "offer is not pre-authorized_code"))
        }
        val proof = proofProvider.provide(ctx.sessionMeta.holderId ?: "anonymous", metadata)
        val authorized = try {
            gateway.authorizeWithPreAuthorizedCode(
                adapterSessionId = ctx.sessionMeta.sessionId.toString(),
                offer = offer,
                metadata = metadata,
                proof = proof,
                txCode = txCode,
            )
        } catch (ex: Exception) {
            return fail(ctx, IssuanceError("pre_authorized_failed", ex.message ?: "pre-authorized failed"))
        }
        val withAuth = ctx.copy(authorizedContext = authorized)
        val saved = transitionAndPersist(withAuth, IssuanceState.AUTHORIZED)
        record(saved, "authorization.pre_authorized", mapOf("txCode" to (if (txCode != null) "provided" else "absent")))
        return saved
    }

    override fun requestCredential(sessionId: UUID, request: IssuanceRequest): IssuanceContext {
        val ctx = loadActive(sessionId)
        require(ctx.state == IssuanceState.AUTHORIZED || ctx.state == IssuanceState.DEFERRED_PENDING) {
            "session $sessionId must be AUTHORIZED or DEFERRED_PENDING (current=${ctx.state})"
        }
        val metadata = ctx.issuerMetadata ?: throwBadRequest("issuer metadata not resolved")
        val proof = proofProvider.provide(ctx.sessionMeta.holderId ?: "anonymous", metadata)
        val requestedCtx = transitionAndPersist(ctx, IssuanceState.CREDENTIAL_REQUESTED)
        record(
            requestedCtx,
            "credential.requested",
            mapOf(
                "configurationId" to (request.credentialConfigurationId ?: ""),
                "credentialIdentifier" to (request.credentialIdentifier ?: ""),
            ),
        )

        val outcome = try {
            gateway.requestCredential(
                adapterSessionId = ctx.sessionMeta.sessionId.toString(),
                request = request,
                proof = proof,
            )
        } catch (ex: Exception) {
            return fail(requestedCtx, IssuanceError("credential_request_failed", ex.message ?: "credential request failed"))
        }

        return when (outcome) {
            is IssuanceOutcome.Issued -> persistIssued(requestedCtx, outcome.credentials, terminalState = IssuanceState.CREDENTIAL_ISSUED)
            is IssuanceOutcome.Deferred -> {
                val withDeferred = requestedCtx.copy(deferredHandle = outcome.handle)
                val saved = transitionAndPersist(withDeferred, IssuanceState.DEFERRED_PENDING)
                record(saved, "credential.deferred", mapOf("transactionId" to outcome.handle.transactionId))
                saved
            }
            is IssuanceOutcome.Failed -> fail(requestedCtx, IssuanceError(outcome.code, outcome.message))
        }
    }

    override fun queryDeferred(sessionId: UUID): IssuanceContext {
        val ctx = loadActive(sessionId)
        require(ctx.state == IssuanceState.DEFERRED_PENDING) {
            "session $sessionId not in DEFERRED_PENDING (current=${ctx.state})"
        }
        val handle = ctx.deferredHandle ?: throwBadRequest("deferred handle missing")
        val outcome = try {
            gateway.queryDeferred(ctx.sessionMeta.sessionId.toString(), handle)
        } catch (ex: Exception) {
            return fail(ctx, IssuanceError("deferred_query_failed", ex.message ?: "deferred query failed"))
        }

        return when (outcome) {
            is DeferredQueryOutcome.Issued -> persistIssued(ctx, outcome.credentials, terminalState = IssuanceState.DEFERRED_ISSUED)
            is DeferredQueryOutcome.StillPending -> {
                val updated = ctx.copy(deferredHandle = outcome.updatedHandle)
                val saved = transitionAndPersist(updated, IssuanceState.DEFERRED_PENDING)
                record(saved, "deferred.pending", mapOf("transactionId" to outcome.updatedHandle.transactionId))
                saved
            }
            is DeferredQueryOutcome.Failed -> fail(ctx, IssuanceError(outcome.code, outcome.message))
        }
    }

    override fun getSession(sessionId: UUID): IssuanceContext {
        val session = repository.findById(sessionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "session $sessionId not found")
        return session.toContext()
    }

    override fun notify(sessionId: UUID, event: NotificationEvent, description: String?): IssuanceContext {
        val ctx = loadActive(sessionId)
        require(ctx.state == IssuanceState.CREDENTIAL_ISSUED || ctx.state == IssuanceState.DEFERRED_ISSUED) {
            "session $sessionId is not in an ISSUED state (current=${ctx.state})"
        }
        val notificationId = ctx.issuedCredentials.firstOrNull()?.notificationId
            ?: throwBadRequest("no notification id available for this session")

        val ok = try {
            gateway.notify(ctx.sessionMeta.sessionId.toString(), notificationId, event, description)
        } catch (ex: Exception) {
            return fail(ctx, IssuanceError("notify_failed", ex.message ?: "notify failed"))
        }

        val outcome = if (ok) event.name else "rejected"
        val saved = transitionAndPersist(ctx.copy(notificationOutcome = outcome), IssuanceState.NOTIFIED)
        gateway.discard(ctx.sessionMeta.sessionId.toString())
        record(saved, "notify.sent", mapOf("event" to event.name, "outcome" to outcome))
        return saved
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private fun persistIssued(
        ctx: IssuanceContext,
        credentials: List<di.swallet.wpb.openid4vci.protocol.IssuedCredential>,
        terminalState: IssuanceState,
    ): IssuanceContext {
        val storedIds = credentials.map { issued ->
            credentialStorage.store(ctx.sessionMeta.holderId ?: "anonymous", issued)
        }
        val withIssued = ctx.copy(issuedCredentials = ctx.issuedCredentials + credentials)
        val saved = transitionAndPersist(withIssued, terminalState)
        record(
            saved,
            "credential.issued",
            mapOf(
                "count" to credentials.size.toString(),
                "storedIds" to storedIds.joinToString(","),
            ),
        )
        return saved
    }

    private fun newContext(holderId: String?, now: Instant): IssuanceContext {
        val sessionId = UUID.randomUUID()
        val expires = now.plus(sessionTtl)
        val correlationId = sessionId.toString()
        return IssuanceContext(
            sessionMeta = IssuanceSessionMetadata(
                sessionId = sessionId,
                holderId = holderId,
                correlationId = correlationId,
                createdAt = now,
                updatedAt = now,
                expiresAt = expires,
                version = 0L,
            ),
            state = IssuanceState.OFFER_RECEIVED,
        )
    }

    private fun loadActive(sessionId: UUID): IssuanceContext {
        val session = repository.findById(sessionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "session $sessionId not found")
        val ctx = session.toContext()
        if (ctx.sessionMeta.expiresAt.isBefore(Instant.now())) {
            persistUpdate(ctx.copy(state = IssuanceState.EXPIRED))
            throw ResponseStatusException(HttpStatus.GONE, "session $sessionId expired")
        }
        if (ctx.state.isTerminal) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "session $sessionId is terminal (${ctx.state})")
        }
        return ctx
    }

    private fun persistNew(ctx: IssuanceContext) {
        repository.create(ctx.toSession())
    }

    private fun persistUpdate(ctx: IssuanceContext): IssuanceContext {
        val updated = repository.update(ctx.toSession())
        return updated.toContext()
    }

    private fun transitionAndPersist(ctx: IssuanceContext, next: IssuanceState): IssuanceContext {
        val updatedState = transitionTo(ctx, next)
        return persistUpdate(updatedState)
    }

    private fun transitionTo(ctx: IssuanceContext, next: IssuanceState): IssuanceContext {
        if (ctx.state == next) return ctx
        require(ctx.state.canTransitionTo(next)) {
            "invalid lifecycle transition ${ctx.state} -> $next"
        }
        return ctx.copy(state = next)
    }

    private fun fail(
        ctx: IssuanceContext,
        error: IssuanceError,
        terminal: IssuanceState = IssuanceState.FAILED,
    ): IssuanceContext {
        require(terminal == IssuanceState.FAILED || terminal == IssuanceState.REJECTED) {
            "fail() must use FAILED or REJECTED state, got $terminal"
        }
        val updated = ctx.copy(error = error)
        val transitioned = if (ctx.state.canTransitionTo(terminal)) {
            updated.copy(state = terminal)
        } else {
            // last-resort: if the current state cannot transition to the target terminal,
            // surface a 500 because that would be a state-machine violation by the caller.
            logger.warn("invalid terminal transition ${ctx.state} -> $terminal; persisting current state with error")
            updated
        }
        val saved = persistUpdate(transitioned)
        record(saved, "lifecycle.failed", mapOf("code" to error.code, "message" to error.message))
        gateway.discard(ctx.sessionMeta.sessionId.toString())
        return saved
    }

    private fun record(ctx: IssuanceContext, type: String, attributes: Map<String, String>) {
        eventStore.record(
            IssuanceEvent(
                sessionId = ctx.sessionMeta.sessionId,
                correlationId = ctx.sessionMeta.correlationId,
                timestamp = Instant.now(),
                type = type,
                state = ctx.state,
                attributes = attributes,
            ),
        )
    }

    private fun throwBadRequest(reason: String): Nothing =
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}
