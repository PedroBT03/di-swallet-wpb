/**
 * Matches verifier DCQL queries against TS5/TS6 registered intended use.
 */

package di.swallet.wpb.presentation.registry

import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.RpRegistryRecord

/**
 * Shared intended-use matching used by registry resolution and policy enforcement.
 */
object RegistryIntendedUseMatcher {
    /** Returns true when every query is covered by at least one registered intended use. */
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

    /** Returns true when any intended use entry includes a privacy policy URI. */
    fun hasPrivacyPolicyUri(record: RpRegistryRecord): Boolean =
        record.intendedUses.any { it.privacyPolicyUris.isNotEmpty() }

    /** Maps wallet credential formats to TS5 registry format strings. */
    fun toTs5Format(format: CredentialFormat): String = when (format) {
        CredentialFormat.SD_JWT -> "dc+sd-jwt"
        CredentialFormat.MDOC -> "mso_mdoc"
    }

    /** Accepts blank descriptor formats or an exact TS5 format match. */
    private fun formatMatches(descriptorFormat: String?, queryFormat: CredentialFormat): Boolean =
        descriptorFormat.isNullOrBlank() ||
            descriptorFormat.equals(toTs5Format(queryFormat), ignoreCase = true)

    /** Accepts empty requested claims or requires every claim to be registered. */
    private fun claimMatches(descriptorClaimPaths: List<String>, requestedClaims: List<String>): Boolean =
        requestedClaims.isEmpty() ||
            requestedClaims.all { claim -> claim in descriptorClaimPaths }
}
