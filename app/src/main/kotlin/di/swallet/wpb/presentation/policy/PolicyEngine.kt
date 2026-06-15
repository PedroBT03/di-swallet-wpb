/**
 * Wallet policy checks for OpenID4VP presentation requests.
 */

package di.swallet.wpb.presentation.policy

import di.swallet.wpb.presentation.domain.PresentationContext

/**
 * Decides whether a presentation request may proceed after trust and matching.
 */
interface PolicyEngine {
    /**
     * Evaluates wallet policy rules and records the decision on the context.
     */
    fun evaluate(context: PresentationContext): PresentationContext
}
