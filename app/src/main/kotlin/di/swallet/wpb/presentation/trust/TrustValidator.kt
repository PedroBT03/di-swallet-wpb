package di.swallet.wpb.presentation.trust

import di.swallet.wpb.presentation.domain.PresentationContext

interface TrustValidator {
    fun validate(context: PresentationContext): PresentationContext
}
