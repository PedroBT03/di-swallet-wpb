/**
 * Builds structured audit attributes for presentation consent events.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.presentation.domain.PresentationContext
import org.springframework.stereotype.Component

/**
 * Produces key-value audit fields for consent pending, granted, and rejected outcomes.
 */
@Component
class ConsentAuditRecorder(
    private val choiceGrouper: CredentialChoiceGrouper,
    private val minimizationEvaluator: AttributeMinimizationEvaluator,
) {

    /**
     * Captures candidate counts, choice groups, and minimization warnings while consent is pending.
     */
    fun presentationPendingAttributes(context: PresentationContext): Map<String, String> {
        val minimization = minimizationEvaluator.evaluate(context)
        val groups = choiceGrouper.group(context.credentialCandidates)
        return mapOf(
            "candidates" to context.credentialCandidates.size.toString(),
            "choiceGroups" to groups.size.toString(),
            "requiresSelectionGroups" to groups.count { it.requiresUserSelection }.toString(),
            "minimizationLevel" to minimization.level.name,
            "warningCodes" to minimization.warnings.joinToString(",") { it.code },
        )
    }

    /**
     * Captures how many credentials were selected and the final minimization level on grant.
     */
    fun presentationGrantedAttributes(selectedCount: Int, minimizationLevel: MinimizationLevel): Map<String, String> =
        mapOf(
            "selected" to selectedCount.toString(),
            "minimizationLevel" to minimizationLevel.name,
        )

    /**
     * Captures the rejection reason supplied by the holder or flow.
     */
    fun presentationRejectedAttributes(reason: String): Map<String, String> =
        mapOf("reason" to reason)
}
