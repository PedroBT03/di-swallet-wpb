package di.swallet.wpb.openid4vp.adapter

import di.swallet.wpb.openid4vp.protocol.AuthorizationRequestResolution
import di.swallet.wpb.openid4vp.protocol.DispatchDetails
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationDispatchOutcome
import di.swallet.wpb.presentation.domain.VpToken

interface OpenId4VpGateway {
    suspend fun resolveRequestUri(requestUri: String): AuthorizationRequestResolution

    suspend fun dispatchPositive(requestToken: String, vpToken: VpToken): PresentationDispatchOutcome

    suspend fun dispatchNegative(requestToken: String): PresentationDispatchOutcome

    suspend fun dispatchError(errorToken: String): PresentationDispatchOutcome
}
