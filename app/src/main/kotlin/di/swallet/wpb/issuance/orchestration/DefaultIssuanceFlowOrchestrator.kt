package di.swallet.wpb.issuance.orchestration

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceError
import di.swallet.wpb.issuance.domain.KaContext
import di.swallet.wpb.issuance.domain.KaState
import di.swallet.wpb.issuance.domain.IssuanceSessionMetadata
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.issuance.domain.WiaContext
import di.swallet.wpb.issuance.domain.WiaState
import di.swallet.wpb.issuance.domain.toContext
import di.swallet.wpb.issuance.domain.toSession
import di.swallet.wpb.issuance.persistence.IssuanceSessionRepository
import di.swallet.wpb.issuance.policy.IssuancePolicy
import di.swallet.wpb.issuance.proof.ProofMaterialProvider
import di.swallet.wpb.issuance.storage.IssuedCredentialStorage
import di.swallet.wpb.issuance.trust.IssuerTrustValidator
import di.swallet.wpb.observability.IssuanceEvent
import di.swallet.wpb.observability.IssuanceEventStore
import di.swallet.wpb.ka.attestation.KeyAttestationProvider
import di.swallet.wpb.ka.validation.KeyAttestationValidationException
import di.swallet.wpb.ka.validation.KeyAttestationValidationService
import di.swallet.wpb.openid4vci.adapter.OpenId4VciGateway
import di.swallet.wpb.openid4vci.protocol.AuthorizationFlowKind
import di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.KeyAttestationTransport
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.openid4vci.protocol.WalletAttestationTransport
import di.swallet.wpb.wia.attestation.WalletAttestationProvider
import di.swallet.wpb.wia.validation.WiaValidationException
import di.swallet.wpb.wia.validation.WiaValidationService
import di.swallet.wpb.service.KeyBindingRuntimeService
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
    private val attestationProvider: WalletAttestationProvider,
    private val wiaValidationService: WiaValidationService,
    private val keyAttestationProvider: KeyAttestationProvider,
    private val keyAttestationValidationService: KeyAttestationValidationService,
    private val credentialStorage: IssuedCredentialStorage,
    private val keyBindingRuntimeService: KeyBindingRuntimeService,
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
        val wia = issueOrReuseWia(ctx, metadata.credentialIssuerId)
        val wiaTransport = wia.toTransport()
        val prepared = try {
            gateway.prepareAuthorization(
                adapterSessionId = ctx.sessionMeta.sessionId.toString(),
                offer = offer,
                metadata = metadata,
                proof = proof,
                walletAttestation = wiaTransport,
            )
        } catch (ex: Exception) {
            return fail(ctx, IssuanceError("authorization_prepare_failed", ex.message ?: "prepare failed"))
        }

        val withAuth = ctx.copy(preparedAuthorization = prepared, wia = WiaContext(state = WiaState.ATTACHED, attestation = wia))
        val saved = transitionAndPersist(withAuth, IssuanceState.AUTHORIZATION_PREPARED)
        record(
            saved,
            "authorization.prepared",
            mapOf(
                "pkce" to prepared.pkceUsed.toString(),
                "par" to prepared.parUsed.toString(),
                "dpop" to prepared.dpopRequested.toString(),
                "wiaAttached" to prepared.wiaAttached.toString(),
            ),
        )
        record(saved, "wia.attached", mapOf("cnfJkt" to wia.cnfJkt, "walletInstanceId" to wia.walletInstanceId))
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
            val wia = ctx.wia?.attestation ?: throwBadRequest("wia missing before authorization")
            gateway.authorizeWithCode(
                adapterSessionId = ctx.sessionMeta.sessionId.toString(),
                authorizationCode = authorizationCode,
                state = state,
                walletAttestation = wia.toTransport(),
            )
        } catch (ex: Exception) {
            return handleWiaAwareAuthorizationFailure(ctx, ex, "authorization_failed")
        }
        val validatedWia = try {
            validateWiaBinding(ctx, authorized)
        } catch (ex: WiaValidationException) {
            return handleWiaValidationFailure(ctx, ex)
        }
        val withAuth = ctx.copy(authorizedContext = authorized, wia = validatedWia)
        val saved = transitionAndPersist(withAuth, IssuanceState.AUTHORIZED)
        record(
            saved,
            "authorization.completed",
            mapOf(
                "accessToken" to authorized.accessTokenPresent.toString(),
                "refreshToken" to authorized.refreshTokenPresent.toString(),
                "dpop" to authorized.dpopUsed.toString(),
                "wiaBound" to "true",
            ),
        )
        record(saved, "wia.binding.verified", mapOf("cnfJkt" to (authorized.wiaCnfJkt ?: "")))
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
        val wia = issueOrReuseWia(ctx, metadata.credentialIssuerId)
        val authorized = try {
            gateway.authorizeWithPreAuthorizedCode(
                adapterSessionId = ctx.sessionMeta.sessionId.toString(),
                offer = offer,
                metadata = metadata,
                proof = proof,
                txCode = txCode,
                walletAttestation = wia.toTransport(),
            )
        } catch (ex: Exception) {
            return handleWiaAwareAuthorizationFailure(ctx.copy(wia = WiaContext(state = WiaState.ATTACHED, attestation = wia)), ex, "pre_authorized_failed")
        }
        val validatedWia = try {
            validateWiaBinding(ctx.copy(wia = WiaContext(state = WiaState.ATTACHED, attestation = wia)), authorized)
        } catch (ex: WiaValidationException) {
            return handleWiaValidationFailure(ctx.copy(wia = WiaContext(state = WiaState.ATTACHED, attestation = wia)), ex)
        }
        val withAuth = ctx.copy(authorizedContext = authorized, wia = validatedWia)
        val saved = transitionAndPersist(withAuth, IssuanceState.AUTHORIZED)
        record(saved, "authorization.pre_authorized", mapOf("txCode" to (if (txCode != null) "provided" else "absent")))
        record(saved, "wia.binding.verified", mapOf("cnfJkt" to (authorized.wiaCnfJkt ?: "")))
        return saved
    }

    override fun requestCredential(sessionId: UUID, request: IssuanceRequest): IssuanceContext {
        val ctx = loadActive(sessionId)
        require(ctx.state == IssuanceState.AUTHORIZED || ctx.state == IssuanceState.DEFERRED_PENDING) {
            "session $sessionId must be AUTHORIZED or DEFERRED_PENDING (current=${ctx.state})"
        }
        val metadata = ctx.issuerMetadata ?: throwBadRequest("issuer metadata not resolved")
        val proof = proofProvider.provide(ctx.sessionMeta.holderId ?: "anonymous", metadata)
        val requestedConfiguration = resolveRequestedConfiguration(ctx, request)
        val requestedCtx = transitionAndPersist(ctx, IssuanceState.CREDENTIAL_REQUESTED)
        record(
            requestedCtx,
            "credential.requested",
            mapOf(
                "configurationId" to (request.credentialConfigurationId ?: ""),
                "credentialIdentifier" to (request.credentialIdentifier ?: ""),
            ),
        )
        val keyAttestation = if (shouldRequireKa(metadata, requestedConfiguration)) {
            val config = requestedConfiguration ?: return fail(
                requestedCtx,
                IssuanceError("key_attestation_missing_configuration", "key attestation requires a resolved credential configuration"),
            )
            try {
                issueOrReuseKa(requestedCtx, metadata, config, proof)
            } catch (ex: KeyAttestationValidationException) {
                if (ex.code == "ka_revoked") {
                    record(
                        requestedCtx,
                        "ka.revoked",
                        mapOf("code" to ex.code, "message" to (ex.message ?: "revoked")),
                    )
                }
                return fail(
                    requestedCtx.copy(
                        ka = (requestedCtx.ka ?: KaContext()).copy(
                            state = if (ex.code.contains("expired")) KaState.EXPIRED else KaState.FAILED,
                            lastErrorCode = ex.code,
                        ),
                    ),
                    IssuanceError(ex.code, ex.message ?: "key attestation validation failed", recoverable = ex.code in setOf("ka_expired", "ka_status_expired")),
                )
            }
        } else {
            null
        }
        val requestedWithKa = persistUpdate(
            requestedCtx.copy(
                ka = if (keyAttestation != null) {
                    (requestedCtx.ka ?: KaContext()).copy(state = KaState.ATTACHED, attestation = keyAttestation, lastErrorCode = null)
                } else {
                    (requestedCtx.ka ?: KaContext()).copy(state = KaState.NOT_REQUIRED, attestation = null, lastErrorCode = null)
                },
            ),
        )
        if (keyAttestation != null) {
            record(
                requestedWithKa,
                "ka.attached",
                mapOf(
                    "keyId" to keyAttestation.keyId,
                    "statusIndex" to keyAttestation.status.index.toString(),
                ),
            )
        }

        val outcome = try {
            gateway.requestCredential(
                adapterSessionId = requestedWithKa.sessionMeta.sessionId.toString(),
                request = request,
                proof = proof,
                keyAttestation = keyAttestation?.toTransport(),
            )
        } catch (ex: Exception) {
            return handleKaAwareFailure(requestedWithKa, ex)
        }

        return when (outcome) {
            is IssuanceOutcome.Issued -> {
                val validated = if (keyAttestation != null) {
                    requestedWithKa.copy(ka = requestedWithKa.ka?.copy(state = KaState.VALIDATED))
                } else {
                    requestedWithKa
                }
                if (keyAttestation != null) {
                    record(
                        validated,
                        "ka.validated",
                        mapOf(
                            "keyId" to keyAttestation.keyId,
                            "attestedJkt" to keyAttestation.attestedJkt,
                        ),
                    )
                }
                persistIssued(
                    validated,
                    outcome.credentials,
                    terminalState = IssuanceState.CREDENTIAL_ISSUED,
                    keyAliasHint = proof.keyId,
                )
            }
            is IssuanceOutcome.Deferred -> {
                val withDeferred = requestedWithKa.copy(deferredHandle = outcome.handle)
                val saved = transitionAndPersist(withDeferred, IssuanceState.DEFERRED_PENDING)
                record(saved, "credential.deferred", mapOf("transactionId" to outcome.handle.transactionId))
                saved
            }
            is IssuanceOutcome.Failed -> fail(requestedWithKa, IssuanceError(outcome.code, outcome.message))
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
            is DeferredQueryOutcome.Issued -> persistIssued(
                ctx,
                outcome.credentials,
                terminalState = IssuanceState.DEFERRED_ISSUED,
                keyAliasHint = null,
            )
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
        keyAliasHint: String?,
    ): IssuanceContext {
        val storedIds = credentials.map { issued ->
            credentialStorage.store(
                holderId = ctx.sessionMeta.holderId ?: "anonymous",
                issued = issued,
                keyAliasHint = keyAliasHint,
            )
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
            wia = WiaContext(state = WiaState.REQUIRED),
            ka = KaContext(state = KaState.REQUIRED),
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

    private fun issueOrReuseWia(ctx: IssuanceContext, issuerId: String?): di.swallet.wpb.issuance.domain.WalletInstanceAttestation {
        if (!properties.wia.enabled) {
            throwBadRequest("wia is disabled")
        }
        val holderId = ctx.sessionMeta.holderId ?: throwBadRequest("holder id required for wia")
        val existing = ctx.wia?.attestation
        if (existing != null && existing.tokenExpiresAt.isAfter(Instant.now()) && existing.clientStatusExpiresAt.isAfter(Instant.now())) {
            return existing
        }
        val issued = attestationProvider.issue(
            holderId = holderId,
            walletInstanceId = holderId,
            issuerId = if (properties.wia.reusePerIssuer) issuerId else null,
        )
        wiaValidationService.validateTechnical(issued)
        return issued
    }

    private fun validateWiaBinding(ctx: IssuanceContext, authorized: di.swallet.wpb.openid4vci.protocol.AuthorizedContext): WiaContext {
        val wia = ctx.wia?.attestation ?: throw WiaValidationException("wia_missing", "WIA not attached to session")
        wiaValidationService.validateTechnical(wia)
        wiaValidationService.validateAccessTokenBinding(wia.cnfJkt, authorized.accessTokenCnfJkt)
        return (ctx.wia ?: WiaContext()).copy(
            state = WiaState.VALIDATED,
            attestation = wia,
            lastErrorCode = null,
        )
    }

    private fun handleWiaAwareAuthorizationFailure(
        ctx: IssuanceContext,
        ex: Exception,
        fallbackCode: String,
    ): IssuanceContext {
        val message = ex.message ?: "authorization failed"
        val lower = message.lowercase()
        return when {
            "nonce" in lower && "mismatch" in lower -> {
                val retries = (ctx.wia?.nonceMismatchRetries ?: 0) + 1
                if (retries > properties.wia.maxNonceMismatchRetries) {
                    fail(
                        ctx.copy(wia = (ctx.wia ?: WiaContext()).copy(state = WiaState.FAILED, nonceMismatchRetries = retries, lastErrorCode = "wia_nonce_mismatch")),
                        IssuanceError("wia_nonce_mismatch", message, recoverable = false),
                    )
                } else {
                    fail(
                        ctx.copy(wia = (ctx.wia ?: WiaContext()).copy(state = WiaState.FAILED, nonceMismatchRetries = retries, lastErrorCode = "wia_nonce_mismatch")),
                        IssuanceError("wia_nonce_mismatch", message, recoverable = true),
                    )
                }
            }
            "wia" in lower && "expired" in lower -> {
                val retries = (ctx.wia?.expiredRetries ?: 0) + 1
                if (retries > properties.wia.maxExpiredRetries) {
                    fail(
                        ctx.copy(wia = (ctx.wia ?: WiaContext()).copy(state = WiaState.EXPIRED, expiredRetries = retries, lastErrorCode = "wia_expired")),
                        IssuanceError("wia_expired", message, recoverable = false),
                    )
                } else {
                    fail(
                        ctx.copy(wia = (ctx.wia ?: WiaContext()).copy(state = WiaState.EXPIRED, expiredRetries = retries, lastErrorCode = "wia_expired")),
                        IssuanceError("wia_expired", message, recoverable = true),
                    )
                }
            }
            else -> fail(ctx, IssuanceError(fallbackCode, message))
        }
    }

    private fun handleWiaValidationFailure(ctx: IssuanceContext, ex: WiaValidationException): IssuanceContext {
        return fail(
            ctx.copy(
                wia = (ctx.wia ?: WiaContext()).copy(
                    state = if (ex.code.contains("expired")) WiaState.EXPIRED else WiaState.FAILED,
                    lastErrorCode = ex.code,
                ),
            ),
            IssuanceError(ex.code, ex.message ?: "wia validation failed", recoverable = ex.code in setOf("wia_expired", "wia_status_expired")),
        )
    }

    private fun shouldRequireKa(
        metadata: di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata,
        configuration: di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor?,
    ): Boolean {
        if (!properties.ka.enabled) return false
        if (configuration == null) return false
        return configuration.keyAttestationRequired ||
            configuration.proofTypesSupported.any { it.equals("attestation", ignoreCase = true) }
    }

    private fun resolveRequestedConfiguration(
        ctx: IssuanceContext,
        request: IssuanceRequest,
    ): di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor? {
        val metadata = ctx.issuerMetadata ?: return null
        val requestedId = request.credentialConfigurationId
            ?: request.credentialIdentifier
            ?: ctx.credentialConfigurationIds.firstOrNull()
        return metadata.credentialConfigurations.firstOrNull { it.id == requestedId }
    }

    private fun issueOrReuseKa(
        ctx: IssuanceContext,
        metadata: di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata,
        configuration: di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor,
        proof: di.swallet.wpb.issuance.proof.ProofMaterial,
    ): di.swallet.wpb.issuance.domain.KeyAttestation {
        val holderId = ctx.sessionMeta.holderId ?: throwBadRequest("holder id required for key attestation")
        val existing = ctx.ka?.attestation
        if (existing != null && existing.tokenExpiresAt.isAfter(Instant.now()) && existing.statusExpiresAt.isAfter(Instant.now())) {
            keyAttestationValidationService.validateTechnical(existing, configuration)
            keyAttestationValidationService.validateTrust(existing, configuration, metadata)
            keyAttestationValidationService.validateBinding(existing, proof)
            keyBindingRuntimeService.registerKeyAttestation(holderId, existing)
            return existing
        }
        val attestation = keyAttestationProvider.issue(
            holderId = holderId,
            issuerId = if (properties.ka.reusePerIssuer) metadata.credentialIssuerId else null,
            metadata = metadata,
            configuration = configuration,
            proofPublicKey = proof.publicKey,
            proofKeyId = proof.keyId,
        )
        keyAttestationValidationService.validateTechnical(attestation, configuration)
        keyAttestationValidationService.validateTrust(attestation, configuration, metadata)
        keyAttestationValidationService.validateBinding(attestation, proof)
        keyBindingRuntimeService.registerKeyAttestation(holderId, attestation)
        record(
            ctx,
            "ka.generated",
            mapOf(
                "keyId" to attestation.keyId,
                "statusIndex" to attestation.status.index.toString(),
                "configurationId" to configuration.id,
            ),
        )
        return attestation
    }

    private fun handleKaAwareFailure(ctx: IssuanceContext, ex: Exception): IssuanceContext {
        val message = ex.message ?: "credential request failed"
        return when (ex) {
            is KeyAttestationValidationException -> {
                if (ex.code == "ka_revoked") {
                    record(
                        ctx,
                        "ka.revoked",
                        mapOf("code" to ex.code, "message" to message),
                    )
                }
                fail(
                    ctx.copy(
                        ka = (ctx.ka ?: KaContext()).copy(
                            state = if (ex.code.contains("expired")) KaState.EXPIRED else KaState.FAILED,
                            lastErrorCode = ex.code,
                        ),
                    ),
                    IssuanceError(ex.code, message, recoverable = ex.code in setOf("ka_expired", "ka_status_expired")),
                )
            }

            else -> fail(ctx, IssuanceError("credential_request_failed", message))
        }
    }

    private fun di.swallet.wpb.issuance.domain.WalletInstanceAttestation.toTransport(): WalletAttestationTransport =
        WalletAttestationTransport(
            jwt = jwt,
            popJwt = popJwt,
            cnfJkt = cnfJkt,
            expiresAt = tokenExpiresAt,
        )

    private fun di.swallet.wpb.issuance.domain.KeyAttestation.toTransport(): KeyAttestationTransport =
        KeyAttestationTransport(
            jwt = jwt,
            keyId = keyId,
            attestedJkt = attestedJkt,
            keyStorage = keyStorage,
            certification = certification,
            expiresAt = tokenExpiresAt,
            statusListUri = status.uri,
            statusListIndex = status.index,
        )
}
