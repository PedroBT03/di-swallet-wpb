/**
 * Placeholder hook for future EU WebAuthn pseudonym profile extensions (PA_21).
 */

package di.swallet.wpb.pseudonym

import org.springframework.stereotype.Component

/**
 * Extension point for the future EU WebAuthn pseudonym profile once normative rules are published.
 */
interface EuPseudonymWebAuthnProfile

/**
 * No-op implementation used until EU pseudonym WebAuthn profile requirements are available.
 */
@Component
class NoOpEuPseudonymWebAuthnProfile : EuPseudonymWebAuthnProfile
