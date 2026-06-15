/**
 * Adapter port for OpenID4VP request resolution and response dispatch.
 */

package di.swallet.wpb.openid4vp.adapter

import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.DispatchDetails
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.VpToken

/**
 * Boundary between orchestration and the EUDI OpenID4VP SDK.
 * Keeps SDK types out of the presentation lifecycle.
 */
interface OpenId4VpGateway {
    /**
     * Resolves a verifier request URI into a normalized authorization request.
     */
    suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution

    /**
     * Sends a positive presentation response containing the built VP token.
     */
    suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome

    /**
     * Sends a negative presentation response after holder rejection or policy failure.
     */
    suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome

    /**
     * Dispatches an authorization request error envelope back to the verifier.
     */
    suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome
}
