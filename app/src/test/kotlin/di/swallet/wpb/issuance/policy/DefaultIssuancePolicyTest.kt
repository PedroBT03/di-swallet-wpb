/**
 * Tests default issuance policy.
 */

package di.swallet.wpb.issuance.policy

import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.domain.IssuanceSessionMetadata
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DefaultIssuancePolicyTest {

    private val policy = DefaultIssuancePolicy()

    /** Builds an OFFER_RESOLVED context requesting [requested] ids against optional [advertised] issuer metadata. */
    private fun ctx(
        requested: List<String>,
        advertised: List<CredentialConfigurationDescriptor> = emptyList(),
    ): IssuanceContext {
        val now = Instant.now()
        val meta = IssuanceSessionMetadata(
            sessionId = UUID.randomUUID(),
            correlationId = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plusSeconds(60),
        )
        val issuerMetadata = if (advertised.isEmpty()) {
            null
        } else {
            ResolvedIssuerMetadata(
                credentialIssuerId = "https://issuer.example",
                credentialConfigurations = advertised,
            )
        }
        return IssuanceContext(
            sessionMeta = meta,
            state = IssuanceState.OFFER_RESOLVED,
            credentialConfigurationIds = requested,
            issuerMetadata = issuerMetadata,
        )
    }

    /**
     * Evaluation with an empty requested configuration list returns allowed=false.
     */
    @Test
    fun `empty configuration list is rejected`() {
        val decision = policy.evaluate(ctx(requested = emptyList()))
        assertFalse(decision.allowed)
    }

    /**
     * Requesting "unknown" when only pid_jwt is advertised is denied with an unsupported reason.
     */
    @Test
    fun `requested but not advertised configuration is rejected`() {
        val advertised = listOf(
            CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC),
        )
        val decision = policy.evaluate(ctx(listOf("unknown"), advertised))
        assertFalse(decision.allowed)
        assertTrue(decision.reason!!.contains("unsupported"))
    }

    /**
     * An advertised mDL configuration is accepted alongside SD-JWT VC.
     */
    @Test
    fun `mdoc configuration is allowed`() {
        val advertised = listOf(
            CredentialConfigurationDescriptor("driver_license", IssuanceCredentialFormat.MSO_MDOC),
        )
        val decision = policy.evaluate(ctx(listOf("driver_license"), advertised))
        assertTrue(decision.allowed)
    }

    /**
     * A requested pid_jwt SD-JWT configuration that is advertised passes.
     */
    @Test
    fun `sd-jwt configuration is allowed`() {
        val advertised = listOf(
            CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC),
        )
        val decision = policy.evaluate(ctx(listOf("pid_jwt"), advertised))
        assertTrue(decision.allowed)
    }

    /**
     * SD-JWT VC and mdoc configurations advertised together are both accepted.
     */
    @Test
    fun `sd-jwt and mdoc configurations are both allowed`() {
        val advertised = listOf(
            CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC),
            CredentialConfigurationDescriptor("driver_license", IssuanceCredentialFormat.MSO_MDOC),
        )
        val decision = policy.evaluate(ctx(listOf("pid_jwt", "driver_license"), advertised))
        assertTrue(decision.allowed)
    }

    /**
     * With issuer metadata still null, a pid_jwt request is allowed pending resolution.
     */
    @Test
    fun `policy is permissive when metadata is not yet resolved`() {
        val decision = policy.evaluate(ctx(listOf("pid_jwt")))
        assertTrue(decision.allowed)
    }
}
