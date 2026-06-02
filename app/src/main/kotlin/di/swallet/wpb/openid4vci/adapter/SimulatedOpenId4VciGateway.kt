package di.swallet.wpb.openid4vci.adapter

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.DeferredIssuanceHandle
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.openid4vci.protocol.AuthorizationFlowKind
import di.swallet.wpb.openid4vci.protocol.AuthorizationServerMetadata
import di.swallet.wpb.openid4vci.protocol.AuthorizedContext
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.openid4vci.protocol.KeyAttestationTransport
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.openid4vci.protocol.PreAuthorizedGrant
import di.swallet.wpb.openid4vci.protocol.PreparedAuthorization
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.openid4vci.protocol.ResolvedOffer
import di.swallet.wpb.openid4vci.protocol.WalletAttestationTransport
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

/**
 * In-process simulated OID4VCI adapter used in demo and tests.
 *
 * The simulator does not contact any external issuer. Instead it parses the
 * offer URI, fabricates deterministic metadata and produces a syntactically
 * valid SD-JWT VC (header + payload only) per request. It is intentionally
 * the only adapter wired by default: production deployments must explicitly
 * switch to a real SDK-backed adapter (see [SdkOpenId4VciGateway]) by
 * setting `wpb.openid4vci.demo-mode=false` and providing the issuer
 * configuration.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "wpb.openid4vci", name = ["demo-mode"], havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(name = ["sdkOpenId4VciGateway"])
class SimulatedOpenId4VciGateway(
    private val properties: OpenId4VciProperties,
    private val mdocDocTypeRegistry: MdocDocTypeRegistry,
    private val mdocCredentialCodec: MdocCredentialCodec,
) : OpenId4VciGateway {

    private data class AdapterState(
        val offer: ResolvedOffer? = null,
        val metadata: ResolvedIssuerMetadata? = null,
        val proof: ProofMaterial? = null,
        val pkceVerifier: String? = null,
        val expectedState: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val cNonce: String? = null,
        val wiaCnfJkt: String? = null,
        val keyAttestationJkt: String? = null,
        var deferredCounter: Int = 0,
    )

    private val states: MutableMap<String, AdapterState> = ConcurrentHashMap()

    override fun resolveOffer(offerUri: String): Pair<ResolvedOffer, ResolvedIssuerMetadata> {
        val params = parseQuery(offerUri)
        val payload = params["credential_offer"]
            ?: params["credential_offer_uri"]?.let { fetchByReference(it) }
            ?: throw IllegalArgumentException("credential offer not present in URI")

        return parseOfferPayload(payload)
    }

    override fun resolveMetadata(
        credentialIssuerId: String,
        credentialConfigurationIds: List<String>,
    ): ResolvedIssuerMetadata = synthesizeMetadata(credentialIssuerId, credentialConfigurationIds)

    override fun prepareAuthorization(
        adapterSessionId: String,
        offer: ResolvedOffer,
        metadata: ResolvedIssuerMetadata,
        proof: ProofMaterial,
        walletAttestation: WalletAttestationTransport,
    ): PreparedAuthorization {
        require(offer.authorizationFlow == AuthorizationFlowKind.AUTHORIZATION_CODE) {
            "offer does not support authorization_code flow"
        }
        val verifier = randomToken(64)
        val state = randomToken(16)
        states.compute(adapterSessionId) { _, prev ->
            (prev ?: AdapterState()).copy(
                offer = offer,
                metadata = metadata,
                proof = proof,
                pkceVerifier = verifier,
                expectedState = state,
                wiaCnfJkt = walletAttestation.cnfJkt,
            )
        }
        val authzEndpoint = metadata.authorizationServers.firstOrNull()?.authorizationEndpoint
            ?: "${metadata.credentialIssuerId.trimEnd('/')}/oauth2/authorize"
        val supportsPar = metadata.authorizationServers.any { it.supportsPar }
        val authzUrl = buildAuthorizationUrl(authzEndpoint, adapterSessionId, state, offer.credentialConfigurationIds)
        return PreparedAuthorization(
            adapterSessionId = adapterSessionId,
            authorizationCodeUrl = authzUrl,
            state = state,
            pkceUsed = true,
            parUsed = supportsPar,
            dpopRequested = metadata.authorizationServers.any { it.supportsDpop },
            wiaAttached = true,
        )
    }

    override fun authorizeWithCode(
        adapterSessionId: String,
        authorizationCode: String,
        state: String,
        walletAttestation: WalletAttestationTransport,
    ): AuthorizedContext {
        val current = states[adapterSessionId]
            ?: throw IllegalStateException("unknown adapter session: $adapterSessionId")
        if (current.expectedState != null && current.expectedState != state) {
            throw IllegalArgumentException("state mismatch")
        }
        require(authorizationCode.isNotBlank()) { "authorization_code must not be blank" }

        val updated = current.copy(
            accessToken = randomToken(48),
            refreshToken = randomToken(48),
            cNonce = randomToken(24),
            wiaCnfJkt = walletAttestation.cnfJkt,
        )
        states[adapterSessionId] = updated
        return AuthorizedContext(
            adapterSessionId = adapterSessionId,
            accessTokenPresent = true,
            refreshTokenPresent = true,
            dpopUsed = current.metadata?.authorizationServers?.any { it.supportsDpop } ?: false,
            cNoncePresent = true,
            authorizationServer = current.metadata?.authorizationServers?.firstOrNull()?.issuer,
            accessTokenCnfJkt = walletAttestation.cnfJkt,
            wiaCnfJkt = walletAttestation.cnfJkt,
        )
    }

    override fun authorizeWithPreAuthorizedCode(
        adapterSessionId: String,
        offer: ResolvedOffer,
        metadata: ResolvedIssuerMetadata,
        proof: ProofMaterial,
        txCode: String?,
        walletAttestation: WalletAttestationTransport,
    ): AuthorizedContext {
        val grant = offer.preAuthorizedGrant
            ?: throw IllegalArgumentException("offer does not contain pre-authorized_code grant")
        if (grant.txCodeRequired) {
            require(!txCode.isNullOrBlank()) { "tx_code required by issuer but not provided" }
        }
        states[adapterSessionId] = AdapterState(
            offer = offer,
            metadata = metadata,
            proof = proof,
            accessToken = randomToken(48),
            refreshToken = randomToken(48),
            cNonce = randomToken(24),
            wiaCnfJkt = walletAttestation.cnfJkt,
        )
        return AuthorizedContext(
            adapterSessionId = adapterSessionId,
            accessTokenPresent = true,
            refreshTokenPresent = false,
            dpopUsed = metadata.authorizationServers.any { it.supportsDpop },
            cNoncePresent = true,
            authorizationServer = metadata.authorizationServers.firstOrNull()?.issuer,
            accessTokenCnfJkt = walletAttestation.cnfJkt,
            wiaCnfJkt = walletAttestation.cnfJkt,
        )
    }

    override fun requestCredential(
        adapterSessionId: String,
        request: IssuanceRequest,
        proof: ProofMaterial,
        keyAttestation: KeyAttestationTransport?,
    ): IssuanceOutcome {
        val state = states[adapterSessionId]
            ?: return IssuanceOutcome.Failed("invalid_session", "unknown adapter session")
        val offer = state.offer
            ?: return IssuanceOutcome.Failed("invalid_state", "offer not resolved")
        val metadata = state.metadata
            ?: return IssuanceOutcome.Failed("invalid_state", "metadata not resolved")

        if (state.accessToken == null) {
            return IssuanceOutcome.Failed("not_authorized", "access token not available")
        }

        val configurationId = request.credentialConfigurationId
            ?: request.credentialIdentifier
            ?: offer.credentialConfigurationIds.firstOrNull()
            ?: return IssuanceOutcome.Failed("invalid_request", "no credential configuration provided")

        val configDescriptor = metadata.credentialConfigurations.firstOrNull { it.id == configurationId }
        val format = configDescriptor?.format ?: IssuanceCredentialFormat.SD_JWT_VC
        val deviceBound = configDescriptor?.keyAttestationRequired ?: false

        if (deviceBound) {
            if (keyAttestation == null) {
                return IssuanceOutcome.Failed("key_attestation_missing", "device-bound issuance requires key attestation")
            }
            if (keyAttestation.attestedJkt.isBlank()) {
                return IssuanceOutcome.Failed("key_attestation_invalid", "attested jkt is missing")
            }
            if (state.cNonce == null) {
                return IssuanceOutcome.Failed("c_nonce_missing", "authorization c_nonce missing")
            }
            states[adapterSessionId] = state.copy(keyAttestationJkt = keyAttestation.attestedJkt)
        }

        if (properties.simulator.alwaysDefer) {
            val handle = DeferredIssuanceHandle(
                transactionId = "tx-${UUID.randomUUID()}",
                notificationId = "notif-${UUID.randomUUID()}",
                serializedContext = encodeContext(adapterSessionId, configurationId),
            )
            return IssuanceOutcome.Deferred(handle)
        }

        val rawPayload = when (format) {
            IssuanceCredentialFormat.SD_JWT_VC ->
                buildFakeSdJwtVc(configurationId, configDescriptor?.vct ?: configurationId, proof)
            IssuanceCredentialFormat.MSO_MDOC ->
                buildFakeMdoc(
                    configurationId = configurationId,
                    docTypeHint = configDescriptor?.docType,
                    vctHint = configDescriptor?.vct,
                )
            IssuanceCredentialFormat.UNKNOWN ->
                buildFakeSdJwtVc(configurationId, configDescriptor?.vct ?: configurationId, proof)
        }
        val credential = IssuedCredential(
            credentialConfigurationId = configurationId,
            format = format,
            rawPayload = rawPayload,
            notificationId = "notif-${UUID.randomUUID()}",
        )
        return IssuanceOutcome.Issued(listOf(credential))
    }

    override fun queryDeferred(
        adapterSessionId: String,
        handle: DeferredIssuanceHandle,
    ): DeferredQueryOutcome {
        val state = states[adapterSessionId]
            ?: return DeferredQueryOutcome.Failed("invalid_session", "unknown adapter session")
        state.deferredCounter += 1

        if (state.deferredCounter < properties.simulator.deferredPollsBeforeIssue) {
            val refreshed = handle.copy(transactionId = "tx-${UUID.randomUUID()}")
            return DeferredQueryOutcome.StillPending(refreshed)
        }

        val (sessionId, configurationId) = decodeContext(handle.serializedContext)
        require(sessionId == adapterSessionId) { "deferred context bound to a different session" }
        val proof = state.proof ?: return DeferredQueryOutcome.Failed("invalid_state", "missing proof material")
        val descriptor = state.metadata?.credentialConfigurations?.firstOrNull { it.id == configurationId }
        val format = descriptor?.format ?: IssuanceCredentialFormat.SD_JWT_VC
        val rawPayload = when (format) {
            IssuanceCredentialFormat.SD_JWT_VC ->
                buildFakeSdJwtVc(configurationId, descriptor?.vct ?: configurationId, proof)
            IssuanceCredentialFormat.MSO_MDOC ->
                buildFakeMdoc(
                    configurationId = configurationId,
                    docTypeHint = descriptor?.docType,
                    vctHint = descriptor?.vct,
                )
            IssuanceCredentialFormat.UNKNOWN ->
                buildFakeSdJwtVc(configurationId, descriptor?.vct ?: configurationId, proof)
        }
        val credential = IssuedCredential(
            credentialConfigurationId = configurationId,
            format = format,
            rawPayload = rawPayload,
            notificationId = handle.notificationId ?: "notif-${UUID.randomUUID()}",
        )
        return DeferredQueryOutcome.Issued(listOf(credential))
    }

    override fun notify(
        adapterSessionId: String,
        notificationId: String,
        event: NotificationEvent,
        description: String?,
    ): Boolean {
        return states[adapterSessionId] != null
    }

    override fun discard(adapterSessionId: String) {
        states.remove(adapterSessionId)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun parseQuery(uri: String): Map<String, String> {
        // The OID4VCI offer URI uses custom schemes such as
        // `openid-credential-offer://?credential_offer=...` or the loose
        // `openid-credential-offer://credential_offer=...`. We accept both
        // by stripping the scheme/authority and then key/value-splitting on
        // `&`.
        val afterScheme = uri.substringAfter("://", uri)
        val candidate = when {
            '?' in afterScheme -> afterScheme.substringAfter('?')
            '=' in afterScheme -> afterScheme
            else -> return emptyMap()
        }
        if (candidate.isBlank()) return emptyMap()
        return candidate.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
                    val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                    key to value
                }
            }
            .toMap()
    }

    private fun fetchByReference(uri: String): String? {
        // Simulator: by-reference offers are not actually fetched. The test harness
        // sends offers by-value or pre-populates the reference store. Returning null
        // makes the caller surface a clear error.
        return null
    }

    private fun parseOfferPayload(payload: String): Pair<ResolvedOffer, ResolvedIssuerMetadata> {
        val cleaned = payload.trim()
        val issuerId = extractJsonString(cleaned, "credential_issuer") ?: "https://issuer.example.org"
        val configIds = extractJsonStringArray(cleaned, "credential_configuration_ids").ifEmpty {
            listOf("eu.europa.ec.eudi.pid_jwt_vc_json")
        }
        val hasPreAuth = cleaned.contains("urn:ietf:params:oauth:grant-type:pre-authorized_code")
        val txRequired = cleaned.contains("\"tx_code\"")
        val preAuthCode = extractJsonString(cleaned, "pre-authorized_code")
        val flow = if (hasPreAuth) AuthorizationFlowKind.PRE_AUTHORIZED_CODE else AuthorizationFlowKind.AUTHORIZATION_CODE
        val grant = if (hasPreAuth) PreAuthorizedGrant(txCodeRequired = txRequired) else null

        val metadata = synthesizeMetadata(issuerId, configIds)
        val offer = ResolvedOffer(
            credentialIssuerId = issuerId,
            credentialConfigurationIds = configIds,
            authorizationFlow = flow,
            preAuthorizedGrant = grant,
            authorizationServer = metadata.authorizationServers.firstOrNull()?.issuer,
            preAuthorizedCode = preAuthCode,
        )
        return offer to metadata
    }

    private fun synthesizeMetadata(
        issuerId: String,
        configurationIds: List<String>,
    ): ResolvedIssuerMetadata {
        val asMeta = AuthorizationServerMetadata(
            issuer = issuerId,
            authorizationEndpoint = "$issuerId/oauth2/authorize",
            tokenEndpoint = "$issuerId/oauth2/token",
            pushedAuthorizationRequestEndpoint = "$issuerId/oauth2/par",
            supportsPar = true,
            supportsDpop = true,
            grantTypesSupported = listOf(
                "authorization_code",
                "urn:ietf:params:oauth:grant-type:pre-authorized_code",
            ),
        )
        val configs = configurationIds.map { id ->
            val normalized = id.lowercase()
            val inferredDocType = mdocDocTypeRegistry.infer(configurationId = id, docTypeHint = null, vctHint = null)
            val mdocById = inferredDocType != null
            val requiresKa = normalized.contains("pid") || normalized.contains("device")
            CredentialConfigurationDescriptor(
                id = id,
                format = if (mdocById) IssuanceCredentialFormat.MSO_MDOC else IssuanceCredentialFormat.SD_JWT_VC,
                docType = inferredDocType?.docType,
                vct = id,
                cryptographicBindingMethodsSupported = listOf("jwk"),
                proofTypesSupported = if (requiresKa) listOf("jwt", "attestation") else listOf("jwt"),
                keyAttestationRequired = requiresKa,
                preferredKeyStorageStatusPeriodDays = if (requiresKa) 31 else null,
                display = listOf(mapOf("name" to id, "locale" to "en")),
            )
        }
        return ResolvedIssuerMetadata(
            credentialIssuerId = issuerId,
            credentialEndpoint = "$issuerId/credential",
            deferredCredentialEndpoint = "$issuerId/credential/deferred",
            notificationEndpoint = "$issuerId/credential/notification",
            signedMetadataPresent = false,
            credentialConfigurations = configs,
            authorizationServers = listOf(asMeta),
        )
    }

    private fun buildAuthorizationUrl(
        endpoint: String,
        adapterSessionId: String,
        state: String,
        configurationIds: List<String>,
    ): String {
        val q = mutableListOf<String>()
        q += "response_type=code"
        q += "state=$state"
        q += "code_challenge_method=S256"
        q += "code_challenge=${randomToken(43)}"
        q += "session=$adapterSessionId"
        q += "scope=${configurationIds.joinToString("+")}"
        val separator = if (endpoint.contains('?')) "&" else "?"
        return "$endpoint$separator${q.joinToString("&")}"
    }

    private fun buildFakeSdJwtVc(
        configurationId: String,
        vct: String,
        proof: ProofMaterial,
    ): String {
        val header = base64Url(
            """{"alg":"ES256","typ":"vc+sd-jwt","kid":"issuer-key-1"}"""
        )
        val payload = base64Url(
            """{"vct":"$vct","iss":"https://issuer.example.org","sub":"holder","cnf":{"jwk":{"kty":"EC","crv":"P-256","alg":"${proof.algorithm}","kid":"${proof.keyId}"}},"_sd":["${randomToken(43)}"]}"""
        )
        val signature = base64Url(randomToken(43))
        val disclosure = base64Url("""["${randomToken(8)}","given_name","Alice"]""")
        return "$header.$payload.$signature~$disclosure~"
    }

    private fun buildFakeMdoc(
        configurationId: String,
        docTypeHint: String?,
        vctHint: String?,
    ): String {
        val definition = mdocDocTypeRegistry.infer(configurationId, docTypeHint, vctHint)
            ?: mdocDocTypeRegistry.all().first()
        val claims = when (definition.docType) {
            "eu.europa.ec.eudi.pid.1" -> mapOf(
                "given_name" to "Alice",
                "family_name" to "Doe",
                "birth_date" to "1990-01-01",
                "nationalities" to listOf("PT"),
            )
            "org.iso.18013.5.1.mDL" -> mapOf(
                "given_name" to "Alice",
                "family_name" to "Doe",
                "birth_date" to "1990-01-01",
                "driving_privileges" to listOf("B"),
            )
            else -> mapOf("given_name" to "Alice")
        }
        return mdocCredentialCodec.encode(
            MdocCredentialDocument(
                docType = definition.docType,
                namespace = definition.namespace,
                claims = claims,
                issuer = "https://issuer.example.org",
                issuedAtEpochSeconds = Instant.now().epochSecond,
            ),
        )
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonStringArray(json: String, key: String): List<String> {
        val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]+)\\]")
        val match = regex.find(json) ?: return emptyList()
        return Regex("\"([^\"]+)\"").findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
    }

    private fun encodeContext(adapterSessionId: String, configurationId: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$adapterSessionId|$configurationId".toByteArray(StandardCharsets.UTF_8))

    private fun decodeContext(serialized: String?): Pair<String, String> {
        require(!serialized.isNullOrBlank()) { "missing deferred context" }
        val raw = String(Base64.getUrlDecoder().decode(serialized), StandardCharsets.UTF_8)
        val parts = raw.split("|", limit = 2)
        require(parts.size == 2) { "malformed deferred context" }
        return parts[0] to parts[1]
    }

    private fun randomToken(length: Int): String {
        val bytes = ByteArray(length)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(length)
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
