/**
 * OpenID4VP protocol models shared by adapters, controllers, and orchestration.
 */

package di.swallet.wpb.openid4vp.protocol

import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationRequirements
import kotlinx.serialization.Serializable
import java.util.UUID

/** Application-level response mode values normalized from OpenID4VP requests. */
enum class PresentationResponseMode {
    DIRECT_POST,
    DIRECT_POST_JWT,
    QUERY,
    QUERY_JWT,
    FRAGMENT,
    FRAGMENT_JWT,
    ;

    /** Returns the wire value used in authorization and dispatch requests. */
    fun wireValue(): String = when (this) {
        DIRECT_POST -> "direct_post"
        DIRECT_POST_JWT -> "direct_post.jwt"
        QUERY -> "query"
        QUERY_JWT -> "query.jwt"
        FRAGMENT -> "fragment"
        FRAGMENT_JWT -> "fragment.jwt"
    }
}

/**
 * Normalized authorization request produced by the OpenID4VP adapter.
 * [requestToken] is opaque and only meaningful inside the adapter boundary.
 */
data class ResolvedAuthorizationRequest(
    val requestToken: String,
    val requestUri: String,
    val clientId: String,
    val responseMode: PresentationResponseMode,
    val nonce: String,
    val state: String?,
    val responseUri: String? = null,
    val redirectUri: String? = null,
    val verifierDisplayName: String? = null,
    val requirements: PresentationRequirements,
    val transactionDataJson: String? = null,
    val verifierInfoJson: String? = null,
)

/** Dispatch target details exposed for observability and error handling. */
data class DispatchDetails(
    val responseMode: PresentationResponseMode,
    val nonce: String?,
    val state: String?,
    val clientId: String?,
    val responseUri: String? = null,
    val redirectUri: String? = null,
)

/** Adapter error returned when the SDK rejects an authorization request. */
data class AuthorizationRequestErrorEnvelope(
    val errorToken: String,
    val errorCode: String,
    val message: String? = null,
    val dispatchDetails: DispatchDetails? = null,
)

/** Result of resolving a verifier request URI through the adapter. */
sealed interface AuthorizationRequestResolution {
    /** Authorization request parsed successfully. */
    data class Success(val request: ResolvedAuthorizationRequest) : AuthorizationRequestResolution

    /** Authorization request was invalid and may include dispatchable error details. */
    data class Invalid(val error: AuthorizationRequestErrorEnvelope) : AuthorizationRequestResolution
}

/** Holder consent submission sent from controllers into orchestration. */
@Serializable
data class ConsentSubmission(
    val sessionId: String,
    val holderId: String,
    val granted: Boolean,
    val selectedCredentialIds: List<String> = emptyList(),
    val reason: String? = null,
)

/** Request to start a presentation session from a verifier request URI. */
@Serializable
data class AuthorizationStartRequest(
    val requestUri: String,
    val holderId: String? = null,
)

/** Pass-through helper for requested credential formats in the application layer. */
fun requestedFormatsFrom(values: Set<CredentialFormat>): Set<CredentialFormat> = values
