/**
 * Validates verifiers against wallet trust anchors and allow-lists.
 */

package di.swallet.wpb.presentation.trust

import di.swallet.wpb.presentation.domain.PresentationContext

/**
 * Checks whether a resolved authorization request comes from a trusted verifier.
 */
interface TrustValidator {
    /**
     * Validates verifier identity and certificate material on the presentation context.
     */
    fun validate(context: PresentationContext): PresentationContext
}
