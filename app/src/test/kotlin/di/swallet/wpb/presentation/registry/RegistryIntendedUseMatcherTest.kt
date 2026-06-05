package di.swallet.wpb.presentation.registry

import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.RegistryCredentialDescriptor
import di.swallet.wpb.presentation.domain.RegistryIntendedUse
import di.swallet.wpb.presentation.domain.RpRegistryRecord
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegistryIntendedUseMatcherTest {

    @Test
    fun `covers matching format and claim paths`() {
        val record = record(claimPaths = listOf("given_name", "family_name"))
        val queries = listOf(
            CredentialQuery(
                id = "pid",
                format = CredentialFormat.SD_JWT,
                requestedClaimPaths = listOf(ClaimPath.key("given_name")),
            ),
        )
        assertTrue(RegistryIntendedUseMatcher.coversQueries(record, queries))
    }

    @Test
    fun `rejects claim outside registered intended use`() {
        val record = record(claimPaths = listOf("family_name"))
        val queries = listOf(
            CredentialQuery(
                id = "pid",
                format = CredentialFormat.SD_JWT,
                requestedClaimPaths = listOf(ClaimPath.key("given_name")),
            ),
        )
        assertFalse(RegistryIntendedUseMatcher.coversQueries(record, queries))
    }

    private fun record(claimPaths: List<String>): RpRegistryRecord = RpRegistryRecord(
        identifier = "rp-123",
        intendedUses = listOf(
            RegistryIntendedUse(
                credentials = listOf(
                    RegistryCredentialDescriptor(format = "dc+sd-jwt", claimPaths = claimPaths),
                ),
            ),
        ),
        rawSignedJwt = "jwt",
        rawJwtPayloadJson = "{}",
        rawDataJson = "{}",
    )
}
