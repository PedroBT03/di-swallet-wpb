package di.swallet.wpb.issuance.policy

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuancePolicyDecision

interface IssuancePolicy {
    fun evaluate(context: IssuanceContext): IssuancePolicyDecision
}
