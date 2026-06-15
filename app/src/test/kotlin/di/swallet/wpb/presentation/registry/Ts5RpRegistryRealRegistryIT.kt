/**
 * Integration tests for ts5 rp registry real registry.
 */

package di.swallet.wpb.presentation.registry

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTags
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Optional smoke test against a real EU/national RP registry deployment.
 *
 * Run only when all env vars are present:
 * - WPB_REAL_REGISTRY_ENABLED=true
 * - WPB_REAL_REGISTRY_BASE_URL=https://...
 * - WPB_REAL_REGISTRY_RP_IDENTIFIER=<wallet relying party id>
 * - WPB_REAL_REGISTRY_VERIFICATION_KEY_PEM_PATHS=<comma-separated PEM paths>
 * Optional:
 * - WPB_REAL_REGISTRY_ALLOWED_HOSTS=<comma-separated hosts; defaults to URL host>
 * - WPB_REAL_REGISTRY_EXPECTED_AUDIENCE=<aud claim expectation>
 */
class Ts5RpRegistryRealRegistryIT {

    /**
     * Real registry env vars enable a production lookup against a configured HTTPS TS5 endpoint.
     * resolveAndValidate returns Accepted with a non-blank identifier and https source endpoint.
     */
    @Test
    @Tag(ConformanceTags.EXTERNAL)
    @ConformanceScenario("vp_real_registry_smoke")
    fun `production https policy accepts signed record from configured registry`() {
        val enabled = System.getenv("WPB_REAL_REGISTRY_ENABLED")?.equals("true", ignoreCase = true) == true
        val baseUrl = System.getenv("WPB_REAL_REGISTRY_BASE_URL")?.trim().orEmpty()
        val rpIdentifier = System.getenv("WPB_REAL_REGISTRY_RP_IDENTIFIER")?.trim().orEmpty()
        val verificationKeys = System.getenv("WPB_REAL_REGISTRY_VERIFICATION_KEY_PEM_PATHS")?.trim().orEmpty()

        assumeTrue(enabled, "real registry test disabled (WPB_REAL_REGISTRY_ENABLED != true)")
        assumeTrue(baseUrl.startsWith("https://"), "WPB_REAL_REGISTRY_BASE_URL must use HTTPS")
        assumeTrue(rpIdentifier.isNotBlank(), "missing WPB_REAL_REGISTRY_RP_IDENTIFIER")
        assumeTrue(verificationKeys.isNotBlank(), "missing WPB_REAL_REGISTRY_VERIFICATION_KEY_PEM_PATHS")

        val allowedHosts = System.getenv("WPB_REAL_REGISTRY_ALLOWED_HOSTS")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: java.net.URI(baseUrl).host

        val props = OpenId4VpProperties().apply {
            demoMode = false
            registry.enabled = true
            registry.baseUrl = baseUrl.trimEnd('/')
            registry.remoteAllowedHosts = allowedHosts
            registry.verificationKeyPemPaths = verificationKeys
            registry.expectedAudience = System.getenv("WPB_REAL_REGISTRY_EXPECTED_AUDIENCE")?.trim().orEmpty()
            registry.requireSignedResponses = true
            registry.requireSignedEnvelopeFields = true
            registry.preferCheckIntendedUseEndpoint = false
        }

        val resolver = RpRegistryResolver(
            properties = props,
            client = Ts5RpRegistryHttpClient(props, WpbMetricsTestSupport.noop()),
            signatureVerifier = RpRegistrySignatureVerifier(props),
        )

        val result = resolver.resolveAndValidate(
            rpIdentifier = rpIdentifier,
            credentialQueries = emptyList(),
        )

        assertTrue(result is RegistryResolution.Accepted, "registry lookup failed: $result")
        val accepted = result as RegistryResolution.Accepted
        assertTrue(accepted.record.identifier.isNotBlank())
        assertTrue(accepted.sourceEndpoint.startsWith("https://"))
    }
}
