package di.swallet.wpb.presentation.trust

import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.TrustDecision
import di.swallet.wpb.presentation.domain.TrustDecisionMode
import di.swallet.wpb.presentation.domain.VerifierIdentity
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import di.swallet.wpb.trust.core.TrustSnapshotResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class DefaultTrustValidator(
    private val properties: OpenId4VpProperties,
    private val trustSnapshotResolver: TrustSnapshotResolver,
    private val certificateExtractor: VerifierCertificateExtractor,
    private val certificateValidationService: AccessCertificateValidationService,
) : TrustValidator {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val supportedPrefixes: Set<String> = setOf(
        "pre-registered",
        "redirect_uri",
        "x509_san_dns",
        "x509_hash",
        "decentralized_identifier",
    )

    override fun validate(context: PresentationContext): PresentationContext {
        val request = context.authorizationRequest
            ?: return context.copy(
                trustDecision = TrustDecision(
                    trusted = false,
                    mode = TrustDecisionMode.REJECTED,
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
                    mode = TrustDecisionMode.REJECTED,
                    reason = "Unsupported client identifier scheme '$prefix' for client_id '$clientId'",
                ),
            )
        }

        val trustAvailability = trustSnapshotResolver.currentAvailability()
        val trustSnapshot = when (trustAvailability) {
            is TrustSnapshotAvailability.Available -> trustAvailability.snapshot
            is TrustSnapshotAvailability.Unavailable -> {
                val failOpenAllowed = properties.demoMode && properties.trust.allowFailOpenInDemoMode
                return if (failOpenAllowed) {
                    logger.warn("event=trust.validation.degraded clientId={} reason={}", clientId, trustAvailability.reason)
                    context.copy(
                        verifierIdentity = identity,
                        trustDecision = TrustDecision(
                            trusted = true,
                            mode = TrustDecisionMode.DEGRADED_DEMO_OPEN,
                            reason = "Trust source unavailable in demo-mode (fail-open): ${trustAvailability.reason}",
                        ),
                    )
                } else {
                    logger.warn("event=trust.validation.failed clientId={} reason={}", clientId, trustAvailability.reason)
                    context.copy(
                        verifierIdentity = identity,
                        trustDecision = TrustDecision(
                            trusted = false,
                            mode = TrustDecisionMode.REJECTED,
                            reason = "Trust source unavailable: ${trustAvailability.reason}",
                        ),
                    )
                }
            }
        }

        val material = certificateExtractor.extract(request)
            ?: return context.copy(
                verifierIdentity = identity,
                trustDecision = TrustDecision(
                    trusted = false,
                    mode = TrustDecisionMode.REJECTED,
                    reason = "Verifier certificate not found in request metadata",
                ),
            )

        when (val certValidation = certificateValidationService.validate(clientId, material, trustSnapshot)) {
            is AccessCertificateValidationResult.Rejected -> {
                return context.copy(
                    verifierIdentity = identity,
                    trustDecision = TrustDecision(
                        trusted = false,
                        mode = TrustDecisionMode.REJECTED,
                        reason = certValidation.reason,
                    ),
                )
            }

            AccessCertificateValidationResult.Trusted -> Unit
        }

        val allowedClientIds = properties.trust.allowedClientIds()
        if (allowedClientIds.isNotEmpty() && clientId !in allowedClientIds) {
            return context.copy(
                verifierIdentity = identity,
                trustDecision = TrustDecision(
                    trusted = false,
                    mode = TrustDecisionMode.REJECTED,
                    reason = "Verifier '$clientId' is not on the configured allow-list",
                ),
            )
        }

        logger.info("event=trust.validation.passed clientId={}", clientId)
        return context.copy(
            verifierIdentity = identity,
            trustDecision = TrustDecision(
                trusted = true,
                mode = TrustDecisionMode.TRUSTED,
                reason = "Verifier certificate validated against trust anchors",
            ),
        )
    }

    private fun extractPrefix(clientId: String): String {
        val separator = clientId.indexOf(':')
        if (separator <= 0) return "pre-registered"
        return clientId.substring(0, separator).lowercase()
    }
}
