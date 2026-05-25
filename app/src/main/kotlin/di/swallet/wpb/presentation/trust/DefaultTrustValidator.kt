package di.swallet.wpb.presentation.trust

import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.TrustDecision
import di.swallet.wpb.presentation.domain.VerifierIdentity
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Trust validator.
 *
 * This is intentionally a thin baseline — full trust chain validation against
 * the List of Trusted Entities (LoTE) and Access Certificate validation are
 * TODO.
 *
 * The validator enforces three baseline guarantees:
 *   1. An authorization request must have been resolved before trust is asked.
 *   2. The client identifier must declare a supported `clientIdPrefix` scheme.
 *   3. If an allow-list of client identifiers is configured, the verifier
 *      must be on it. An empty allow-list is treated as "open" (acceptable
 *      for local protocol validation, never for production).
 */
@Service
class DefaultTrustValidator(
    @param:Value("\${wpb.openid4vp.trust.allowed-client-ids:}") private val allowedClientIdsCsv: String,
    @param:Value("\${wpb.openid4vp.demo-mode:false}") private val demoMode: Boolean,
) : TrustValidator {

    private val supportedPrefixes: Set<String> = setOf(
        "pre-registered",
        "redirect_uri",
        "x509_san_dns",
        "x509_hash",
        "decentralized_identifier",
    )

    private val allowedClientIds: Set<String> =
        allowedClientIdsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    override fun validate(context: PresentationContext): PresentationContext {
        val request = context.authorizationRequest
            ?: return context.copy(
                trustDecision = TrustDecision(
                    trusted = false,
                    reason = "Authorization request must be resolved before trust validation",
                ),
            )

        val clientId = request.clientId
        val prefix = extractPrefix(clientId)
        val identity = VerifierIdentity(
            clientId = clientId,
            displayName = request.verifierDisplayName ?: clientId,
            clientIdPrefix = prefix,
        )

        val prefixSupported = supportedPrefixes.any { it.equals(prefix, ignoreCase = true) }
        if (!prefixSupported) {
            return context.copy(
                verifierIdentity = identity,
                trustDecision = TrustDecision(
                    trusted = false,
                    reason = "Unsupported client identifier scheme '$prefix' for client_id '$clientId'",
                ),
            )
        }

        val onAllowList = allowedClientIds.isEmpty() || clientId in allowedClientIds
        if (!onAllowList && !demoMode) {
            return context.copy(
                verifierIdentity = identity,
                trustDecision = TrustDecision(
                    trusted = false,
                    reason = "Verifier '$clientId' is not on the configured trust list",
                ),
            )
        }

        val reason = when {
            allowedClientIds.isEmpty() -> "Trust list not configured: accepting verifier by default (Phase 1 baseline)"
            else -> "Verifier '$clientId' is on the configured trust list"
        }

        return context.copy(
            verifierIdentity = identity,
            trustDecision = TrustDecision(
                trusted = true,
                reason = reason,
            ),
        )
    }

    private fun extractPrefix(clientId: String): String {
        val separator = clientId.indexOf(':')
        if (separator <= 0) return "pre-registered"
        return clientId.substring(0, separator).lowercase()
    }
}
