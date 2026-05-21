package di.swallet.wpb.presentation.matching

import di.swallet.wpb.presentation.domain.PresentationContext

interface CredentialMatcher {
    fun match(context: PresentationContext): PresentationContext
}
