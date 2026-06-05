package di.swallet.wpb.presentation.registry

import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.RpRegistryRecord

/**
 * Shared TS5/TS6 intended-use matching used by registry resolution and policy enforcement.
 */
object RegistryIntendedUseMatcher {
    fun coversQueries(record: RpRegistryRecord, credentialQueries: List<CredentialQuery>): Boolean {
        if (record.intendedUses.isEmpty()) return false
        if (credentialQueries.isEmpty()) return true
        return credentialQueries.all { query ->
            record.intendedUses.any { intendedUse ->
                intendedUse.credentials.any { descriptor ->
                    formatMatches(descriptor.format, query.format) &&
                        claimMatches(descriptor.claimPaths, query.requestedClaims)
                }
            }
        }
    }

    fun hasPrivacyPolicyUri(record: RpRegistryRecord): Boolean =
        record.intendedUses.any { it.privacyPolicyUris.isNotEmpty() }

    fun toTs5Format(format: CredentialFormat): String = when (format) {
        CredentialFormat.SD_JWT -> "dc+sd-jwt"
        CredentialFormat.MDOC -> "mso_mdoc"
    }

    private fun formatMatches(descriptorFormat: String?, queryFormat: CredentialFormat): Boolean =
        descriptorFormat.isNullOrBlank() ||
            descriptorFormat.equals(toTs5Format(queryFormat), ignoreCase = true)

    private fun claimMatches(descriptorClaimPaths: List<String>, requestedClaims: List<String>): Boolean =
        requestedClaims.isEmpty() ||
            requestedClaims.all { claim -> claim in descriptorClaimPaths }
}
