/**
 * Builds OpenID4VP verifiable presentation tokens from selected credentials.
 */

package di.swallet.wpb.presentation.format

import di.swallet.wpb.presentation.domain.PresentationContext

/**
 * Produces a VP token and attaches it to the presentation context.
 */
interface VpTokenBuilder {
    /**
     * Encodes selected credentials into a VP token for the current session.
     */
    fun build(context: PresentationContext): PresentationContext
}
