package di.swallet.wpb.presentation.format

import di.swallet.wpb.presentation.domain.PresentationContext

interface VpTokenBuilder {
    fun build(context: PresentationContext): PresentationContext
}
