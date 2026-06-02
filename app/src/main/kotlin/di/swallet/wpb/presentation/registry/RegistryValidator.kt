package di.swallet.wpb.presentation.registry

import di.swallet.wpb.presentation.domain.PresentationContext

interface RegistryValidator {
    fun validate(context: PresentationContext): PresentationContext
}
