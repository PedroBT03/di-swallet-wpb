/**
 * Validates verifiers against the TS5/TS6 RP registry.
 */

package di.swallet.wpb.presentation.registry

import di.swallet.wpb.presentation.domain.PresentationContext

/**
 * Resolves and validates relying-party registry data for a presentation request.
 */
interface RegistryValidator {
    /**
     * Looks up the verifier in the registry and records the decision on the context.
     */
    fun validate(context: PresentationContext): PresentationContext
}
