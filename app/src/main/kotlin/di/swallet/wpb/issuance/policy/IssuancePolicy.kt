/**
 * Wallet-side policy gate evaluated before OID4VCI issuance proceeds.
 */

package di.swallet.wpb.issuance.policy

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuancePolicyDecision

/** Evaluates whether an issuance session satisfies configured wallet policy. */
interface IssuancePolicy {
    /** Returns allow/deny with a reason when the session violates policy. */
    fun evaluate(context: IssuanceContext): IssuancePolicyDecision
}
