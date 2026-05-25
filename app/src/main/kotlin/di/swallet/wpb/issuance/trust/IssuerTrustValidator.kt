package di.swallet.wpb.issuance.trust

import di.swallet.wpb.issuance.domain.IssuanceTrustDecision
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata

interface IssuerTrustValidator {
    fun validate(metadata: ResolvedIssuerMetadata): IssuanceTrustDecision
}
