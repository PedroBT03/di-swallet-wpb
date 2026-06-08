package di.swallet.wpb.pseudonym

import org.springframework.stereotype.Component

/**
 * Placeholder for the future EU WebAuthn pseudonym profile (PA_21).
 * No normative extensions are implemented until the Commission specification is published.
 */
interface EuPseudonymWebAuthnProfile

@Component
class NoOpEuPseudonymWebAuthnProfile : EuPseudonymWebAuthnProfile
