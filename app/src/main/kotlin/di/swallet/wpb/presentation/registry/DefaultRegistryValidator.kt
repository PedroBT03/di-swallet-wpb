/**
 * Default registry validation for OpenID4VP presentation requests.
 */

package di.swallet.wpb.presentation.registry

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.RegistryDecision
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Resolves RP registry records and records acceptance or rejection on the context.
 */
@Service
class DefaultRegistryValidator(
    private val properties: OpenId4VpProperties,
    private val resolver: RpRegistryResolver,
) : RegistryValidator {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Skips, accepts, or rejects registry validation based on configuration and trust state.
     */
    override fun validate(context: PresentationContext): PresentationContext {
        if (!properties.registry.enabled) {
            return context.copy(
                registryDecision = RegistryDecision(
                    accepted = true,
                    reason = "Registry validation disabled by configuration",
                ),
            )
        }

        val trustTrusted = context.trustDecision?.trusted == true
        if (!trustTrusted) {
            return context.copy(
                registryDecision = RegistryDecision(
                    accepted = false,
                    reason = "Registry validation requires trusted verifier",
                ),
            )
        }

        val request = context.authorizationRequest
            ?: return context.copy(
                registryDecision = RegistryDecision(
                    accepted = false,
                    reason = "Authorization request must be resolved before registry validation",
                ),
            )

        val resolution = resolver.resolveAndValidate(
            rpIdentifier = request.clientId,
            credentialQueries = request.requirements.credentialQueries,
        )
        return when (resolution) {
            is RegistryResolution.Accepted -> {
                logger.info(
                    "event=registry.validation.passed identifier={} endpoint={} intendedUseChecked={}",
                    resolution.record.identifier,
                    resolution.sourceEndpoint,
                    resolution.intendedUseChecked,
                )
                context.copy(
                    registryRecord = resolution.record,
                    registryDecision = RegistryDecision(
                        accepted = true,
                        reason = "Registry validation passed (${properties.registry.specificationVersion})",
                        rpIdentifier = resolution.record.identifier,
                        sourceEndpoint = resolution.sourceEndpoint,
                        intendedUseChecked = resolution.intendedUseChecked,
                    ),
                )
            }

            is RegistryResolution.Rejected -> {
                logger.warn(
                    "event=registry.validation.failed clientId={} reason={} endpoint={}",
                    request.clientId,
                    resolution.reason,
                    resolution.sourceEndpoint,
                )
                context.copy(
                    registryDecision = RegistryDecision(
                        accepted = false,
                        reason = resolution.reason,
                        rpIdentifier = request.clientId,
                        sourceEndpoint = resolution.sourceEndpoint,
                    ),
                )
            }
        }
    }
}
