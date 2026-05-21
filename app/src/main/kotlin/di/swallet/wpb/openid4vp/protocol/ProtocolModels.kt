package di.swallet.wpb.openid4vp.protocol

import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationRequirements
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Normalized response mode values used inside the application.
 */
enum class PresentationResponseMode {
    DIRECT_POST,
    DIRECT_POST_JWT,
    QUERY,
    QUERY_JWT,
    FRAGMENT,
    FRAGMENT_JWT,
    ;

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
 * Public representation of the verifier request resolved by the SDK adapter.
 * The requestToken is opaque and only meaningful inside the adapter boundary.
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

/**
 * Dispatch details exposed to the application for observability.
 */
data class DispatchDetails(
    val responseMode: PresentationResponseMode,
    val nonce: String?,
    val state: String?,
    val clientId: String?,
    val responseUri: String? = null,
    val redirectUri: String? = null,
)

/**
 * Error envelope returned by the adapter when the SDK rejects an authorization request.
 */
data class AuthorizationRequestErrorEnvelope(
    val errorToken: String,
    val errorCode: String,
    val message: String? = null,
    val dispatchDetails: DispatchDetails? = null,
)

/**
 * Resolution result from the adapter.
 */
sealed interface AuthorizationRequestResolution {
    data class Success(val request: ResolvedAuthorizationRequest) : AuthorizationRequestResolution

    data class Invalid(val error: AuthorizationRequestErrorEnvelope) : AuthorizationRequestResolution
}

/**
 * Submission request used by controllers and orchestration.
 */
@Serializable
data class ConsentSubmission(
    val sessionId: String,
    val granted: Boolean,
    val selectedCredentialIds: List<String> = emptyList(),
    val reason: String? = null,
)

/**
 * Session start request used by controllers.
 */
@Serializable
data class AuthorizationStartRequest(
    val requestUri: String,
    val holderId: String? = null,
)

/**
 * Simple helper for obtaining a list of requested formats from the application layer.
 */
fun requestedFormatsFrom(values: Set<CredentialFormat>): Set<CredentialFormat> = values
