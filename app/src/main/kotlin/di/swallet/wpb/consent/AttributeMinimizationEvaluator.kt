/**
 * Holder-facing attribute minimization warnings for presentation consent screens.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.registry.RegistryIntendedUseMatcher
import org.springframework.stereotype.Component

/**
 * Produces holder-facing minimization warnings only.
 * Blocking decisions remain in [di.swallet.wpb.presentation.policy.DefaultPolicyEngine].
 */
@Component
class AttributeMinimizationEvaluator(
    private val openId4VpProperties: OpenId4VpProperties,
) {

    /**
     * Builds holder-facing warnings when requested claims may exceed the RP's registered intended use.
     */
    fun evaluate(context: PresentationContext): MinimizationAssessment {
        val warnings = mutableListOf<ConsentWarning>()

        if (!openId4VpProperties.registry.enabled) {
            warnings += ConsentWarning(
                code = "registry_validation_disabled",
                message = "RP registry validation is disabled; intended-use coverage cannot be verified",
            )
        } else {
            val registry = context.registryRecord
            val queries = context.presentationRequirements?.credentialQueries.orEmpty()
            if (registry != null && queries.isNotEmpty()) {
                if (context.registryDecision?.intendedUseChecked != true) {
                    warnings += ConsentWarning(
                        code = "intended_use_not_checked",
                        message = "Registry intended-use verification was not completed",
                    )
                } else if (!RegistryIntendedUseMatcher.coversQueries(registry, queries)) {
                    warnings += ConsentWarning(
                        code = "requested_claims_exceed_registry",
                        message = "Requested attributes exceed the RP registered intended use",
                    )
                }
            }
            if (registry == null && context.registryDecision?.accepted == true) {
                warnings += ConsentWarning(
                    code = "registry_record_missing",
                    message = "Registry validation succeeded but no registry record is available for review",
                )
            }
        }

        val level = if (warnings.isEmpty()) MinimizationLevel.OK else MinimizationLevel.WARNING
        return MinimizationAssessment(level = level, warnings = warnings)
    }
}
