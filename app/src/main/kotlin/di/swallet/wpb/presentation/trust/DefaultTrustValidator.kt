package di.swallet.wpb.presentation.trust

import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.VerifierIdentity
import di.swallet.wpb.presentation.domain.TrustDecision
import org.springframework.stereotype.Service

@Service
class DefaultTrustValidator : TrustValidator {
    override fun validate(context: PresentationContext): PresentationContext {
        val request = requireNotNull(context.authorizationRequest) { "Authorization request must be resolved before trust validation" }
        val prefix = request.clientId.substringBefore(':', missingDelimiterValue = "pre-registered")
        val verifierIdentity = VerifierIdentity(
            clientId = request.clientId,
            displayName = request.verifierDisplayName ?: request.clientId,
            clientIdPrefix = prefix,
        )

        return context.copy(
            verifierIdentity = verifierIdentity,
            trustDecision = TrustDecision(
                trusted = true,
                reason = "SDK request resolution accepted",
            ),
        )
    }
}
