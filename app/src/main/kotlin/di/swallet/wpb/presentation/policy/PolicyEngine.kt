package di.swallet.wpb.presentation.policy

import di.swallet.wpb.presentation.domain.PresentationContext

interface PolicyEngine {
    fun evaluate(context: PresentationContext): PresentationContext
}
