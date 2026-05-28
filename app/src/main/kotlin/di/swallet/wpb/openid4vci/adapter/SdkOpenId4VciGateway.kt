package di.swallet.wpb.openid4vci.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.DeferredIssuanceHandle
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.proof.ProofMaterial
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
import eu.europa.ec.eudi.openid4vci.CredentialIssuerId
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataResolver
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production path for OID4VCI when demo-mode is disabled.
 *
 * This adapter keeps SDK-facing concerns isolated from orchestration and
 * executes the full gateway contract over issuer HTTP endpoints.
 */
@Component("sdkOpenId4VciGateway")
@ConditionalOnProperty(prefix = "wpb.openid4vci", name = ["demo-mode"], havingValue = "false")
class SdkOpenId4VciGateway(
    private val properties: OpenId4VciProperties,
    private val proofJwtSigner: ProofJwtSigner,
) : OpenId4VciGateway {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val states = ConcurrentHashMap<String, AdapterState>()
    private val sdkHttpClient = HttpClient(CIO)
    private val sdkOfferResolver = CredentialOfferRequestResolver.Companion.invoke { sdkHttpClient }
    private val sdkMetadataResolver = CredentialIssuerMetadataResolver.Companion.invoke { sdkHttpClient }

    init {
        logger.info("SdkOpenId4VciGateway enabled. Issuer hint: '{}'", properties.sdk.credentialIssuerId)
    }

    private data class AdapterState(
        val offer: ResolvedOffer? = null,
        val metadata: ResolvedIssuerMetadata? = null,
        val proof: ProofMaterial? = null,
        val pkceVerifier: String? = null,
        val expectedState: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val cNonce: String? = null,
    )

    override fun resolveOffer(offerUri: String): Pair<ResolvedOffer, ResolvedIssuerMetadata> {
        return runCatching {
            val sdkOffer = runBlocking { sdkOfferResolver.resolve(offerUri) }.getOrThrow()
            val issuer = sdkOffer.credentialIssuerIdentifier.toString()
            val configIds = sdkOffer.credentialConfigurationIdentifiers.map { it.toString() }
            val preAuth = sdkOffer.grants?.preAuthorizedCode()
            val flow = if (preAuth != null) AuthorizationFlowKind.PRE_AUTHORIZED_CODE else AuthorizationFlowKind.AUTHORIZATION_CODE
            val preGrant = preAuth?.let {
                PreAuthorizedGrant(
                    txCodeRequired = it.txCode != null,
                    txCodeDescription = it.txCode?.description,
                    txCodeLength = it.txCode?.length,
                )
            }
            val metadata = resolveMetadata(issuer, configIds)
            ResolvedOffer(
                credentialIssuerId = issuer,
                credentialConfigurationIds = configIds,
                authorizationFlow = flow,
                preAuthorizedGrant = preGrant,
                authorizationServer = metadata.authorizationServers.firstOrNull()?.issuer,
                preAuthorizedCode = preAuth?.preAuthorizedCode,
            ) to metadata
        }.recoverCatching { ex ->
            if (properties.sdk.strictResolution) {
                throw IllegalStateException("SDK offer resolver failed in strict mode: ${ex.message}", ex)
            }
            logger.warn("SDK offer resolver failed (falling back to local parser): {}", ex.message)
            resolveOfferFallback(offerUri)
        }.getOrThrow()
    }

    override fun resolveMetadata(
        credentialIssuerId: String,
        credentialConfigurationIds: List<String>,
    ): ResolvedIssuerMetadata {
        val normalizedIssuer = credentialIssuerId.trimEnd('/')
        runCatching {
            // Use official SDK resolver as first-class metadata integration.
            val sdkIssuerId = CredentialIssuerId(normalizedIssuer).getOrThrow()
            runBlocking { sdkMetadataResolver.resolve(sdkIssuerId) }.getOrThrow()
        }.onFailure {
            if (properties.sdk.strictResolution) {
                throw IllegalStateException("SDK metadata resolver failed in strict mode: ${it.message}", it)
            }
            logger.warn("SDK metadata resolver failed; continuing with direct metadata fetch: {}", it.message)
        }
        val metadataEndpoint = "$normalizedIssuer/.well-known/openid-credential-issuer"
        val metadataRaw = restClient.get()
            .uri(metadataEndpoint)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("empty issuer metadata response")
        val metadataNode = mapper.readTree(metadataRaw)
        val authServers = metadataNode["authorization_servers"]?.mapNotNull { it.asText() }?.takeIf { it.isNotEmpty() }
            ?: listOf(normalizedIssuer)

        val configurations = extractConfigurations(metadataNode, credentialConfigurationIds)
        val authorizationMetadata = authServers.map { asIssuer ->
            val authMetadata = fetchAuthorizationServerMetadata(asIssuer)
            AuthorizationServerMetadata(
                issuer = asIssuer,
                authorizationEndpoint = authMetadata["authorization_endpoint"]?.asText(),
                tokenEndpoint = authMetadata["token_endpoint"]?.asText(),
                pushedAuthorizationRequestEndpoint = authMetadata["pushed_authorization_request_endpoint"]?.asText(),
                supportsPar = authMetadata.hasNonNull("pushed_authorization_request_endpoint"),
                supportsDpop = authMetadata["dpop_signing_alg_values_supported"]?.isArray == true,
                grantTypesSupported = authMetadata["grant_types_supported"]?.mapNotNull { it.asText() } ?: emptyList(),
            )
        }
        return ResolvedIssuerMetadata(
            credentialIssuerId = normalizedIssuer,
            credentialEndpoint = metadataNode["credential_endpoint"]?.asText(),
            deferredCredentialEndpoint = metadataNode["deferred_credential_endpoint"]?.asText(),
            notificationEndpoint = metadataNode["notification_endpoint"]?.asText(),
            signedMetadataPresent = metadataNode.hasNonNull("signed_metadata"),
            credentialConfigurations = configurations,
            authorizationServers = authorizationMetadata,
        )
    }

    private fun resolveOfferFallback(offerUri: String): Pair<ResolvedOffer, ResolvedIssuerMetadata> {
        val params = parseQuery(offerUri)
        val payload = params["credential_offer"]
            ?: params["credential_offer_uri"]?.let { fetchOfferReference(it) }
            ?: throw IllegalArgumentException("credential offer not present in URI")
        val root = mapper.readTree(payload)
        val issuer = root["credential_issuer"]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("credential_issuer missing in offer")
        val configIds = root["credential_configuration_ids"]?.mapNotNull { it.asText() } ?: emptyList()
        val grants = root["grants"]
        val preAuthNode = grants?.get("urn:ietf:params:oauth:grant-type:pre-authorized_code")
        val flow = if (preAuthNode != null && !preAuthNode.isNull) AuthorizationFlowKind.PRE_AUTHORIZED_CODE else AuthorizationFlowKind.AUTHORIZATION_CODE
        val preGrant = preAuthNode?.let {
            val tx = it["tx_code"]
            PreAuthorizedGrant(
                txCodeRequired = tx != null,
                txCodeDescription = tx?.get("description")?.asText(),
                txCodeLength = tx?.get("length")?.asInt(),
            )
        }
        val preAuthorizedCode = preAuthNode?.get("pre-authorized_code")?.asText()
        val metadata = resolveMetadata(issuer, configIds)
        return ResolvedOffer(
            credentialIssuerId = issuer,
            credentialConfigurationIds = configIds,
            authorizationFlow = flow,
            preAuthorizedGrant = preGrant,
            authorizationServer = metadata.authorizationServers.firstOrNull()?.issuer,
            preAuthorizedCode = preAuthorizedCode,
        ) to metadata
    }

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
        states[adapterSessionId] = AdapterState(
            offer = offer,
            metadata = metadata,
            proof = proof,
            pkceVerifier = verifier,
            expectedState = state,
        )
        val authEndpoint = metadata.authorizationServers.firstOrNull()?.authorizationEndpoint
            ?: throw IllegalStateException("authorization endpoint not available")
        val authUrl = buildAuthorizationUrl(
            endpoint = authEndpoint,
            adapterSessionId = adapterSessionId,
            state = state,
            verifier = verifier,
            offer = offer,
            walletAttestation = walletAttestation,
        )
        return PreparedAuthorization(
            adapterSessionId = adapterSessionId,
            authorizationCodeUrl = authUrl,
            state = state,
            pkceUsed = true,
            parUsed = metadata.authorizationServers.any { it.supportsPar },
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
        val session = states[adapterSessionId]
            ?: throw IllegalStateException("unknown adapter session: $adapterSessionId")
        require(!authorizationCode.isBlank()) { "authorization code must not be blank" }
        if (session.expectedState != null && state != session.expectedState) {
            throw IllegalArgumentException("state mismatch")
        }
        val tokenEndpoint = session.metadata?.authorizationServers?.firstOrNull()?.tokenEndpoint
            ?: throw IllegalStateException("token endpoint missing")
        val tokenBody = mapOf(
            "grant_type" to "authorization_code",
            "code" to authorizationCode,
            "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
            "client_id" to "wpb-wallet",
            "code_verifier" to (session.pkceVerifier ?: ""),
            "wallet_attestation" to walletAttestation.jwt,
            "wallet_attestation_pop" to walletAttestation.popJwt,
        )
        val tokenResponse = postJson(tokenEndpoint, tokenBody)
        val accessToken = tokenResponse["access_token"]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("access token missing")
        val refreshToken = tokenResponse["refresh_token"]?.asText()
        val cNonce = tokenResponse["c_nonce"]?.asText()
        states[adapterSessionId] = session.copy(
            accessToken = accessToken,
            refreshToken = refreshToken,
            cNonce = cNonce,
        )
        return AuthorizedContext(
            adapterSessionId = adapterSessionId,
            accessTokenPresent = true,
            refreshTokenPresent = !refreshToken.isNullOrBlank(),
            dpopUsed = session.metadata?.authorizationServers?.any { it.supportsDpop } ?: false,
            cNoncePresent = !cNonce.isNullOrBlank(),
            authorizationServer = session.metadata?.authorizationServers?.firstOrNull()?.issuer,
            accessTokenCnfJkt = tokenResponse["cnf"]?.get("jkt")?.asText(),
            wiaCnfJkt = tokenResponse["cnf"]?.get("jkt")?.asText(),
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
        val tokenEndpoint = metadata.authorizationServers.firstOrNull()?.tokenEndpoint
            ?: throw IllegalStateException("token endpoint missing")
        val grant = offer.preAuthorizedGrant
        if (grant?.txCodeRequired == true && txCode.isNullOrBlank()) {
            throw IllegalArgumentException("tx_code required by issuer but not provided")
        }
        val preAuthCode = offer.preAuthorizedCode
            ?: throw IllegalArgumentException("pre-authorized code missing in offer")
        val tokenBody = mutableMapOf<String, Any?>(
            "grant_type" to "urn:ietf:params:oauth:grant-type:pre-authorized_code",
            "pre-authorized_code" to preAuthCode,
            "wallet_attestation" to walletAttestation.jwt,
            "wallet_attestation_pop" to walletAttestation.popJwt,
        )
        if (!txCode.isNullOrBlank()) tokenBody["tx_code"] = txCode
        val tokenResponse = postJson(tokenEndpoint, tokenBody)
        val accessToken = tokenResponse["access_token"]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("access token missing")
        val refreshToken = tokenResponse["refresh_token"]?.asText()
        val cNonce = tokenResponse["c_nonce"]?.asText()
        states[adapterSessionId] = AdapterState(
            offer = offer,
            metadata = metadata,
            proof = proof,
            accessToken = accessToken,
            refreshToken = refreshToken,
            cNonce = cNonce,
        )
        return AuthorizedContext(
            adapterSessionId = adapterSessionId,
            accessTokenPresent = true,
            refreshTokenPresent = !refreshToken.isNullOrBlank(),
            dpopUsed = metadata.authorizationServers.any { it.supportsDpop },
            cNoncePresent = !cNonce.isNullOrBlank(),
            authorizationServer = metadata.authorizationServers.firstOrNull()?.issuer,
            accessTokenCnfJkt = tokenResponse["cnf"]?.get("jkt")?.asText(),
            wiaCnfJkt = tokenResponse["cnf"]?.get("jkt")?.asText(),
        )
    }

    override fun requestCredential(
        adapterSessionId: String,
        request: IssuanceRequest,
        proof: ProofMaterial,
        keyAttestation: KeyAttestationTransport?,
    ): IssuanceOutcome {
        val session = states[adapterSessionId]
            ?: return IssuanceOutcome.Failed("invalid_session", "unknown adapter session")
        val metadata = session.metadata
            ?: return IssuanceOutcome.Failed("invalid_state", "issuer metadata missing")
        val endpoint = metadata.credentialEndpoint
            ?: return IssuanceOutcome.Failed("invalid_metadata", "credential endpoint missing")
        val accessToken = session.accessToken
            ?: return IssuanceOutcome.Failed("not_authorized", "access token missing")
        val proofJwt = runCatching {
            proofJwtSigner.sign(
                proof = proof,
                audience = metadata.credentialIssuerId,
                cNonce = session.cNonce,
            )
        }.getOrElse {
            return IssuanceOutcome.Failed("proof_signing_failed", it.message ?: "proof signing failed")
        }
        val proofJwk = mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "alg" to proof.algorithm,
            "kid" to proof.keyId,
        )
        val payload = mutableMapOf<String, Any?>(
            "credential_configuration_id" to request.credentialConfigurationId,
            "credential_identifier" to request.credentialIdentifier,
            "proof" to mapOf(
                "proof_type" to "jwt",
                "jwt" to proofJwt,
                "jwk" to proofJwk,
            ),
        )
        if (request.claims.isNotEmpty()) {
            payload["claims"] = request.claims
        }
        if (keyAttestation != null) {
            payload["proof"] = mapOf(
                "proof_type" to "attestation",
                "jwt" to proofJwt,
                "attestation" to mapOf("key_attestation" to keyAttestation.jwt),
                "jwk" to proofJwk,
            )
        }
        return try {
            val responseBody = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { it.setBearerAuth(accessToken) }
                .body(payload)
                .retrieve()
                .body(String::class.java)
            val raw = responseBody ?: throw IllegalStateException("empty credential response")
            val node = mapper.readTree(raw)
            if (node.hasNonNull("transaction_id")) {
                val handle = DeferredIssuanceHandle(
                    transactionId = node["transaction_id"].asText(),
                    notificationId = node["notification_id"]?.asText(),
                    serializedContext = node["transaction_id"].asText(),
                )
                return IssuanceOutcome.Deferred(handle)
            }
            val credentialPayload = node["credential"]?.asText() ?: raw
            IssuanceOutcome.Issued(
                credentials = listOf(
                    IssuedCredential(
                        credentialConfigurationId = request.credentialConfigurationId ?: request.credentialIdentifier ?: "unknown",
                        format = IssuanceCredentialFormat.SD_JWT_VC,
                        rawPayload = credentialPayload,
                        notificationId = node["notification_id"]?.asText(),
                    ),
                ),
            )
        } catch (ex: Exception) {
            logger.warn("SDK credential request failed: {}", ex.message)
            IssuanceOutcome.Failed("sdk_credential_failed", ex.message ?: "sdk credential request failed")
        }
    }

    override fun queryDeferred(
        adapterSessionId: String,
        handle: DeferredIssuanceHandle,
    ): DeferredQueryOutcome {
        val session = states[adapterSessionId]
            ?: return DeferredQueryOutcome.Failed("invalid_session", "unknown adapter session")
        val endpoint = session.metadata?.deferredCredentialEndpoint
            ?: return DeferredQueryOutcome.Failed("invalid_metadata", "deferred credential endpoint missing")
        val accessToken = session.accessToken
            ?: return DeferredQueryOutcome.Failed("not_authorized", "access token missing")
        return try {
            val response = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { it.setBearerAuth(accessToken) }
                .body(mapOf("transaction_id" to handle.transactionId))
                .retrieve()
                .body(String::class.java)
                ?: throw IllegalStateException("empty deferred response")
            val node = mapper.readTree(response)
            val credential = node["credential"]?.asText()
            if (!credential.isNullOrBlank()) {
                DeferredQueryOutcome.Issued(
                    listOf(
                        IssuedCredential(
                            credentialConfigurationId = "deferred",
                            format = IssuanceCredentialFormat.SD_JWT_VC,
                            rawPayload = credential,
                            notificationId = node["notification_id"]?.asText() ?: handle.notificationId,
                        ),
                    ),
                )
            } else {
                DeferredQueryOutcome.StillPending(
                    handle.copy(
                        transactionId = node["transaction_id"]?.asText() ?: handle.transactionId,
                        notificationId = node["notification_id"]?.asText() ?: handle.notificationId,
                        serializedContext = node["transaction_id"]?.asText() ?: handle.serializedContext,
                    ),
                )
            }
        } catch (ex: Exception) {
            DeferredQueryOutcome.Failed("sdk_deferred_failed", ex.message ?: "sdk deferred query failed")
        }
    }

    override fun notify(
        adapterSessionId: String,
        notificationId: String,
        event: NotificationEvent,
        description: String?,
    ): Boolean {
        val session = states[adapterSessionId] ?: return false
        val endpoint = session.metadata?.notificationEndpoint ?: return false
        val accessToken = session.accessToken ?: return false
        return runCatching {
            restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { it.setBearerAuth(accessToken) }
                .body(
                    mapOf(
                        "notification_id" to notificationId,
                        "event" to event.name,
                        "description" to description,
                    ),
                )
                .retrieve()
                .toBodilessEntity()
            true
        }.getOrDefault(false)
    }

    override fun discard(adapterSessionId: String) {
        states.remove(adapterSessionId)
    }

    private fun extractConfigurations(
        metadataNode: JsonNode,
        requestedIds: List<String>,
    ): List<CredentialConfigurationDescriptor> {
        val supported = metadataNode["credential_configurations_supported"] ?: return emptyList()
        val resolvedIds = if (requestedIds.isNotEmpty()) requestedIds else supported.fieldNames().asSequence().toList()
        return resolvedIds.mapNotNull { id ->
            val node = supported[id] ?: return@mapNotNull null
            val formatRaw = node["format"]?.asText()?.lowercase() ?: "sd_jwt_vc"
            val format = when (formatRaw) {
                "sd_jwt_vc", "dc+sd-jwt" -> IssuanceCredentialFormat.SD_JWT_VC
                "mso_mdoc" -> IssuanceCredentialFormat.MSO_MDOC
                else -> IssuanceCredentialFormat.UNKNOWN
            }
            val proofTypesNode = node["proof_types_supported"]
            val proofTypes = when {
                proofTypesNode == null -> emptyList()
                proofTypesNode.isArray -> proofTypesNode.mapNotNull { it.asText() }
                proofTypesNode.isObject -> proofTypesNode.fieldNames().asSequence().toList()
                else -> emptyList()
            }
            val bindingMethods = node["cryptographic_binding_methods_supported"]?.mapNotNull { it.asText() } ?: emptyList()
            val keyAttestationRequired = node["key_attestation_required"]?.asBoolean(false) == true ||
                proofTypes.any { it.equals("attestation", ignoreCase = true) }
            CredentialConfigurationDescriptor(
                id = id,
                format = format,
                docType = node["doctype"]?.asText(),
                vct = node["vct"]?.asText(),
                cryptographicBindingMethodsSupported = bindingMethods,
                proofTypesSupported = proofTypes,
                keyAttestationRequired = keyAttestationRequired,
                preferredKeyStorageStatusPeriodDays = node["preferred_key_storage_status_period_days"]?.asInt(),
                display = node["display"]?.mapNotNull { displayNode ->
                    if (!displayNode.isObject) return@mapNotNull null
                    displayNode.fields().asSequence().associate { it.key to it.value.asText() }
                } ?: emptyList(),
            )
        }
    }

    private fun fetchAuthorizationServerMetadata(issuer: String): JsonNode {
        val endpoint = "${issuer.trimEnd('/')}/.well-known/oauth-authorization-server"
        val body = restClient.get()
            .uri(endpoint)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("empty authorization server metadata")
        return mapper.readTree(body)
    }

    private fun fetchOfferReference(referenceUrl: String): String =
        restClient.get()
            .uri(referenceUrl)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("empty credential offer reference response")

    private fun parseQuery(uri: String): Map<String, String> {
        val query = when {
            '?' in uri -> uri.substringAfter('?')
            "openid-credential-offer://" in uri -> uri.substringAfter("openid-credential-offer://")
            else -> return emptyMap()
        }
        if (query.isBlank()) return emptyMap()
        return query
            .split('&')
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8)
                val v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8)
                k to v
            }
            .toMap()
    }

    private fun buildAuthorizationUrl(
        endpoint: String,
        adapterSessionId: String,
        state: String,
        verifier: String,
        offer: ResolvedOffer,
        walletAttestation: WalletAttestationTransport,
    ): String {
        val codeChallenge = sha256Base64Url(verifier)
        val ids = offer.credentialConfigurationIds.joinToString(" ")
        val sep = if ('?' in endpoint) '&' else '?'
        return buildString {
            append(endpoint)
            append(sep)
            append("response_type=code")
            append("&client_id=wpb-wallet")
            append("&redirect_uri=urn:ietf:wg:oauth:2.0:oob")
            append("&scope=openid")
            append("&state=").append(state)
            append("&code_challenge=").append(codeChallenge)
            append("&code_challenge_method=S256")
            append("&credential_configuration_ids=").append(urlEncode(ids))
            append("&wallet_attestation=").append(urlEncode(walletAttestation.jwt))
            append("&wallet_attestation_pop=").append(urlEncode(walletAttestation.popJwt))
            append("&adapter_session_id=").append(urlEncode(adapterSessionId))
        }
    }

    private fun postJson(endpoint: String, body: Map<String, Any?>): JsonNode {
        val response = restClient.post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("empty response from $endpoint")
        return mapper.readTree(response)
    }

    private fun randomToken(size: Int): String {
        val bytes = ByteArray(size)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
}
