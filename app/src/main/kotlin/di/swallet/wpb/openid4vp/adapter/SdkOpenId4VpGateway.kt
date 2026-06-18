/**
 * EUDI OpenID4VP SDK adapter for request resolution and response dispatch.
 */

package di.swallet.wpb.openid4vp.adapter

import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestErrorEnvelope
import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.DcqlSupport
import di.swallet.wpb.openid4vp.protocol.DispatchDetails
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.VpToken
import eu.europa.ec.eudi.openid4vp.AuthorizationRequestError
import eu.europa.ec.eudi.openid4vp.Consensus
import eu.europa.ec.eudi.openid4vp.DispatchOutcome
import eu.europa.ec.eudi.openid4vp.EncryptionParameters
import eu.europa.ec.eudi.openid4vp.ErrorDispatchDetails
import eu.europa.ec.eudi.openid4vp.OpenId4Vp
import eu.europa.ec.eudi.openid4vp.Resolution
import eu.europa.ec.eudi.openid4vp.ResolvedRequestObject
import eu.europa.ec.eudi.openid4vp.ResponseMode
import eu.europa.ec.eudi.openid4vp.VerifiablePresentation
import eu.europa.ec.eudi.openid4vp.VerifiablePresentations
import eu.europa.ec.eudi.openid4vp.dcql.QueryId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the presentation orchestrator to the EUDI OpenID4VP SDK.
 * Keeps SDK request objects inside the adapter and exposes normalized DTOs outward.
 */
@Service
class SdkOpenId4VpGateway(
    private val openId4Vp: OpenId4Vp,
    @param:Value("\${wpb.openid4vp.demo-mode:false}") private val demoMode: Boolean,
) : OpenId4VpGateway {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val requestStore = ConcurrentHashMap<String, ResolvedRequestObject>()
    private val fallbackRequestStore = ConcurrentHashMap<String, FallbackRequest>()
    private val errorStore = ConcurrentHashMap<String, Pair<AuthorizationRequestError, ErrorDispatchDetails?>>()

    private val secureRandom = java.security.SecureRandom()

    /**
     * Returns fresh ephemeral encryption material for a single dispatch.
     *
     * Phase 1 does not bind the encryption parameters to the resolved request
     * (full HAIP response encryption is tracked under Phase 5/8). Each
     * dispatch still gets a per-request random value so that no two sessions
     * share key material in memory.
     */
    private fun ephemeralEncryptionParameters(): EncryptionParameters {
        val randomBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        return EncryptionParameters.DiffieHellman(Base64URL.encode(randomBytes))
    }

    /** Resolves a verifier request URI through the SDK, with demo-mode fallback parsing. */
    override suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution {
        if (demoMode && looksLikeLocalEmulatorRequestUri(requestUri)) {
            when (val emulatorResolution = resolveRequestUriFallback(requestUri)) {
                is AuthorizationRequestResolution.Success -> return emulatorResolution
                is AuthorizationRequestResolution.Invalid -> return emulatorResolution
                null -> Unit
            }
        }

        return when (val resolution = openId4Vp.resolveRequestUri(requestUri)) {
            is Resolution.Success -> {
                val sdkRequest = resolution.requestObject
                val token = UUID.randomUUID().toString()
                requestStore[token] = sdkRequest

                val request = sdkRequest.toPublicRequest(token, requestUri)
                AuthorizationRequestResolution.Success(request)
            }

            is Resolution.Invalid -> {
                if (!demoMode) {
                    val token = UUID.randomUUID().toString()
                    errorStore[token] = resolution.error to resolution.dispatchDetails
                    val publicError = resolution.error.toPublicError(token, resolution.dispatchDetails)
                    return AuthorizationRequestResolution.Invalid(publicError)
                }
                when (val fallback = resolveRequestUriFallback(requestUri)) {
                    null -> {
                        val token = UUID.randomUUID().toString()
                        errorStore[token] = resolution.error to resolution.dispatchDetails
                        val publicError = resolution.error.toPublicError(token, resolution.dispatchDetails)
                        AuthorizationRequestResolution.Invalid(publicError)
                    }
                    else -> fallback
                }
            }
        }
    }

    /** Parses authorization request JSON directly when demo-mode fallback is enabled. */
    private fun resolveRequestUriFallback(requestUri: String): AuthorizationRequestResolution? {
        return try {
            val raw = fetchRequestBody(requestUri)
            val jsonText = decodeJwtPayloadIfNeeded(raw)
            val element = Json.parseToJsonElement(jsonText).jsonObject
            val clientId = extractClientId(element)
                ?: run {
                    val emulatorError = element["error"]?.jsonPrimitive?.contentOrNull
                    if (emulatorError != null) {
                        return invalidFallback(
                            "VerifierEmulatorError",
                            "Verifier emulator returned an error: $emulatorError",
                        )
                    }
                    return invalidFallback(
                        "MissingClientId",
                        "Authorization request is missing client_id (check verifier-emulator is running and pip install -r requirements.txt)",
                    )
                }
            val responseMode = when (element["response_mode"]?.jsonPrimitive?.contentOrNull) {
                "direct_post" -> PresentationResponseMode.DIRECT_POST
                "direct_post.jwt" -> PresentationResponseMode.DIRECT_POST_JWT
                "query" -> PresentationResponseMode.QUERY
                "query.jwt" -> PresentationResponseMode.QUERY_JWT
                "fragment" -> PresentationResponseMode.FRAGMENT
                "fragment.jwt" -> PresentationResponseMode.FRAGMENT_JWT
                else -> PresentationResponseMode.DIRECT_POST
            }
            val nonce = element["nonce"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
            val state = element["state"]?.jsonPrimitive?.contentOrNull
            val redirectUri = element["redirect_uri"]?.jsonPrimitive?.contentOrNull
            val responseUri = element["response_uri"]?.jsonPrimitive?.contentOrNull
            val dcqlElement = element["dcql"] as? JsonObject
            val dcqlJson = dcqlElement?.toString() ?: "{}"
            val parsedQueries = DcqlSupport.parse(dcqlJson)
            val queryIds = parsedQueries.map { it.id }
            val requestedFormats = parsedQueries.map { it.format }.toSet().ifEmpty { setOf(CredentialFormat.SD_JWT) }

            val requestToken = UUID.randomUUID().toString()
            val request = ResolvedAuthorizationRequest(
                requestToken = requestToken,
                requestUri = requestUri,
                clientId = clientId,
                responseMode = responseMode,
                nonce = nonce,
                state = state,
                responseUri = responseUri,
                redirectUri = redirectUri,
                verifierDisplayName = clientId,
                requirements = PresentationRequirements(
                    dcqlQueryJson = dcqlJson,
                    credentialQueryIds = queryIds,
                    requestedFormats = requestedFormats,
                    credentialQueries = parsedQueries,
                ),
                transactionDataJson = element["transaction_data"]?.toString(),
                verifierInfoJson = encodeVerifierInfoJson(element["verifier_info"]),
            )
            fallbackRequestStore[requestToken] = FallbackRequest(
                requestToken = requestToken,
                requestUri = requestUri,
                clientId = clientId,
                responseMode = responseMode,
                nonce = nonce,
                state = state,
                responseUri = responseUri,
                redirectUri = redirectUri,
            )
            AuthorizationRequestResolution.Success(request)
        } catch (t: Throwable) {
            logger.warn("Fallback request resolution failed for {}: {}", requestUri, t.message)
            null
        }
    }

    /** Decodes a JWT payload when the fetched request body is a compact JWS. */
    private fun decodeJwtPayloadIfNeeded(raw: String): String {
        val parts = raw.trim().split('.')
        if (parts.size == 3) {
            val payload = parts[1]
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            return String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
        }
        return raw
    }

    /** Builds an invalid fallback resolution with a fresh error token. */
    private fun invalidFallback(code: String, message: String = code): AuthorizationRequestResolution.Invalid {
        val token = UUID.randomUUID().toString()
        val envelope = AuthorizationRequestErrorEnvelope(token, code, message)
        return AuthorizationRequestResolution.Invalid(envelope)
    }

    /** Fetches the authorization request body from a request_uri with basic HTTP status checks. */
    private fun fetchRequestBody(requestUri: String): String {
        val connection = URI(requestUri).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (status !in 200..299) {
            throw IllegalStateException("HTTP $status from $requestUri: $body")
        }
        return body
    }

    /** Reads client_id from common authorization request JSON shapes. */
    private fun extractClientId(element: JsonObject): String? =
        element["client_id"]?.jsonPrimitive?.contentOrNull
            ?: element["clientId"]?.jsonPrimitive?.contentOrNull

    /** Serializes verifier_info for PKIX extraction downstream. */
    private fun encodeVerifierInfoJson(verifierInfo: kotlinx.serialization.json.JsonElement?): String? {
        if (verifierInfo == null) return null
        return Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), verifierInfo)
    }

    /** True for the local Flask verifier emulator used in thesis demos. */
    private fun looksLikeLocalEmulatorRequestUri(requestUri: String): Boolean {
        return runCatching {
            val uri = URI(requestUri)
            val host = uri.host?.lowercase()
            val path = uri.path.orEmpty()
            val localHost = host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            val emulatorPath = path.startsWith("/request/")
            localHost && (emulatorPath || requestUri.contains(":8081"))
        }.getOrDefault(false)
    }

    /** Dispatches a positive presentation response through the SDK or demo fallback path. */
    override suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome {
        val sdkRequest = requestStore[requestToken]
        if (sdkRequest != null) {
            val consensus = Consensus.PositiveConsensus(vpToken.toSdkVerifiablePresentations())
            val outcome = dispatchOutcome(openId4Vp.dispatch(sdkRequest, consensus, ephemeralEncryptionParameters()))
            requestStore.remove(requestToken)
            return outcome
        }
        val fallback = fallbackRequestStore[requestToken] ?: return PresentationDispatchOutcome.VerifierRejected
        val outcome = dispatchFallbackPositive(fallback, vpToken)
        fallbackRequestStore.remove(requestToken)
        return outcome
    }

    /** Dispatches a negative presentation response through the SDK or demo fallback path. */
    override suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome {
        val sdkRequest = requestStore[requestToken]
        if (sdkRequest != null) {
            val outcome = dispatchOutcome(openId4Vp.dispatch(sdkRequest, Consensus.NegativeConsensus, ephemeralEncryptionParameters()))
            requestStore.remove(requestToken)
            return outcome
        }
        val fallback = fallbackRequestStore[requestToken] ?: return PresentationDispatchOutcome.VerifierRejected
        val outcome = dispatchFallbackNegative(fallback)
        fallbackRequestStore.remove(requestToken)
        return outcome
    }

    /** Dispatches an authorization request error envelope back to the verifier. */
    override suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome {
        val (sdkError, sdkDispatchDetails) = errorStore[errorToken] ?: return PresentationDispatchOutcome.VerifierRejected
        val details = sdkDispatchDetails ?: return PresentationDispatchOutcome.VerifierRejected
        val outcome = dispatchOutcome(openId4Vp.dispatchError(sdkError, details, ephemeralEncryptionParameters()))
        errorStore.remove(errorToken)
        return outcome
    }

    /** Maps an SDK dispatch outcome into the presentation domain model. */
    private fun dispatchOutcome(outcome: DispatchOutcome): PresentationDispatchOutcome = when (outcome) {
        is DispatchOutcome.RedirectURI -> PresentationDispatchOutcome.RedirectUri(outcome.value)
        is DispatchOutcome.VerifierResponse.Accepted -> PresentationDispatchOutcome.VerifierAccepted(outcome.redirectURI)
        DispatchOutcome.VerifierResponse.Rejected -> PresentationDispatchOutcome.VerifierRejected
    }

    /** Converts the wallet VP token into SDK verifiable presentation objects. */
    private fun VpToken.toSdkVerifiablePresentations(): VerifiablePresentations {
        val map = presentationsByQueryId.mapKeys { QueryId(it.key) }.mapValues { (_, values) ->
            values.map { VerifiablePresentation.Generic(it) }
        }
        return VerifiablePresentations(map)
    }

    /** Maps a resolved SDK request object into the public authorization request DTO. */
    private fun ResolvedRequestObject.toPublicRequest(requestToken: String, requestUri: String): ResolvedAuthorizationRequest {
        val queryIds = query.credentials.value.map { it.id.value }
        val formats = buildSet {
            if (vpFormatsSupported?.sdJwtVc != null) add(CredentialFormat.SD_JWT)
            if (vpFormatsSupported?.msoMdoc != null) add(CredentialFormat.MDOC)
        }.ifEmpty { setOf(CredentialFormat.SD_JWT) }

        val dcqlJson = query.toString()
        val parsedQueries = DcqlSupport.parse(dcqlJson)

        return ResolvedAuthorizationRequest(
            requestToken = requestToken,
            requestUri = requestUri,
            clientId = client.id.clientId,
            responseMode = responseMode.toPublicMode(),
            nonce = nonce,
            state = state,
            responseUri = responseMode.responseUriOrNull(),
            redirectUri = responseMode.redirectUriOrNull(),
            verifierDisplayName = client.clientDisplayName(),
            requirements = PresentationRequirements(
                dcqlQueryJson = dcqlJson,
                credentialQueryIds = queryIds,
                requestedFormats = formats,
                credentialQueries = parsedQueries,
            ),
            transactionDataJson = transactionData?.toString(),
            verifierInfoJson = verifierInfo?.toString(),
        )
    }

    /** Sends a positive demo fallback response directly to the verifier endpoint. */
    private fun dispatchFallbackPositive(fallback: FallbackRequest, vpToken: VpToken): PresentationDispatchOutcome {
        return try {
            val target = fallback.responseUri ?: fallback.redirectUri
            if (target == null) return PresentationDispatchOutcome.VerifierRejected
            val firstPresentation = vpToken.presentationsByQueryId.values.flatten().firstOrNull() ?: ""

            when (fallback.responseMode) {
                PresentationResponseMode.DIRECT_POST, PresentationResponseMode.DIRECT_POST_JWT -> {
                    val payload = buildString {
                        append('{')
                        append("\"state\":")
                        appendQuotedJson(fallback.state ?: "")
                        append(',')
                        append("\"vp_token\":")
                        appendQuotedJson(firstPresentation)
                        append('}')
                    }
                    val conn = URL(target).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code in 200..299) PresentationDispatchOutcome.VerifierAccepted(null) else PresentationDispatchOutcome.VerifierRejected
                }
                else -> PresentationDispatchOutcome.RedirectUri(URI(target))
            }
        } catch (t: Throwable) {
            logger.warn("Fallback positive dispatch failed for {}: {}", fallback.requestToken, t.message)
            PresentationDispatchOutcome.VerifierRejected
        }
    }

    /** Sends a negative demo fallback response directly to the verifier endpoint. */
    private fun dispatchFallbackNegative(fallback: FallbackRequest): PresentationDispatchOutcome {
        return try {
            val target = fallback.responseUri ?: fallback.redirectUri
            if (target == null) return PresentationDispatchOutcome.VerifierRejected
            when (fallback.responseMode) {
                PresentationResponseMode.DIRECT_POST, PresentationResponseMode.DIRECT_POST_JWT -> {
                    val conn = URL(target).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }
                    if (conn.responseCode in 200..299) PresentationDispatchOutcome.VerifierAccepted(null) else PresentationDispatchOutcome.VerifierRejected
                }
                else -> PresentationDispatchOutcome.RedirectUri(URI(target))
            }
        } catch (t: Throwable) {
            logger.warn("Fallback negative dispatch failed for {}: {}", fallback.requestToken, t.message)
            PresentationDispatchOutcome.VerifierRejected
        }
    }

    /** Appends a JSON-escaped string literal to the builder. */
    private fun StringBuilder.appendQuotedJson(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    /** Minimal authorization request state used by the demo fallback dispatch path. */
    private data class FallbackRequest(
        val requestToken: String,
        val requestUri: String,
        val clientId: String,
        val responseMode: PresentationResponseMode,
        val nonce: String,
        val state: String?,
        val responseUri: String?,
        val redirectUri: String?,
    )

    /** Maps an SDK authorization request error into the public error envelope. */
    private fun AuthorizationRequestError.toPublicError(token: String, details: ErrorDispatchDetails?): AuthorizationRequestErrorEnvelope {
        return AuthorizationRequestErrorEnvelope(
            errorToken = token,
            errorCode = this::class.simpleName ?: "authorization_request_error",
            message = toString(),
            dispatchDetails = details?.toPublicDispatchDetails(),
        )
    }

    /** Maps SDK error dispatch details into the public dispatch details DTO. */
    private fun ErrorDispatchDetails.toPublicDispatchDetails(): DispatchDetails = DispatchDetails(
        responseMode = responseMode.toPublicMode(),
        nonce = nonce,
        state = state,
        clientId = clientId?.clientId,
        responseUri = responseMode.responseUriOrNull(),
        redirectUri = responseMode.redirectUriOrNull(),
    )

    /** Maps an SDK response mode into the application enum. */
    private fun ResponseMode.toPublicMode(): PresentationResponseMode = when (this) {
        is ResponseMode.DirectPost -> PresentationResponseMode.DIRECT_POST
        is ResponseMode.DirectPostJwt -> PresentationResponseMode.DIRECT_POST_JWT
        is ResponseMode.Query -> PresentationResponseMode.QUERY
        is ResponseMode.QueryJwt -> PresentationResponseMode.QUERY_JWT
        is ResponseMode.Fragment -> PresentationResponseMode.FRAGMENT
        is ResponseMode.FragmentJwt -> PresentationResponseMode.FRAGMENT_JWT
    }

    /** Returns the response URI for direct-post response modes. */
    private fun ResponseMode.responseUriOrNull(): String? = when (this) {
        is ResponseMode.DirectPost -> responseURI.toString()
        is ResponseMode.DirectPostJwt -> responseURI.toString()
        else -> null
    }

    /** Returns the redirect URI for query and fragment response modes. */
    private fun ResponseMode.redirectUriOrNull(): String? = when (this) {
        is ResponseMode.Query -> redirectUri.toString()
        is ResponseMode.QueryJwt -> redirectUri.toString()
        is ResponseMode.Fragment -> redirectUri.toString()
        is ResponseMode.FragmentJwt -> redirectUri.toString()
        else -> null
    }

    /** Returns the verifier display name, defaulting to the client id. */
    private fun eu.europa.ec.eudi.openid4vp.Client.clientDisplayName(): String? = id.clientId
}
