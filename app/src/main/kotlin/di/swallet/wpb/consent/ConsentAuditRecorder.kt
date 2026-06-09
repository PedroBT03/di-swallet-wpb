package di.swallet.wpb.consent

import di.swallet.wpb.presentation.domain.PresentationContext
import org.springframework.stereotype.Component

@Component
class ConsentAuditRecorder(
    private val choiceGrouper: CredentialChoiceGrouper,
    private val minimizationEvaluator: AttributeMinimizationEvaluator,
) {

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

    fun presentationGrantedAttributes(selectedCount: Int, minimizationLevel: MinimizationLevel): Map<String, String> =
        mapOf(
            "selected" to selectedCount.toString(),
            "minimizationLevel" to minimizationLevel.name,
        )

    fun presentationRejectedAttributes(reason: String): Map<String, String> =
        mapOf("reason" to reason)
}
