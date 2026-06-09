package di.swallet.wpb.openid4vci.adapter

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTags
import di.swallet.wpb.config.OpenId4VciProperties
import org.junit.jupiter.api.Tag
import di.swallet.wpb.issuance.proof.ProofMaterial
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Optional smoke test against a real issuer.
 *
 * Run only when both env vars are present:
 * - WPB_REAL_ISSUER_ENABLED=true
 * - WPB_REAL_ISSUER_OFFER_URI=<openid-credential-offer://... or https://...>
 */
class SdkOpenId4VciGatewayRealIssuerIT {

    @Test
    @Tag(ConformanceTags.EXTERNAL)
    @ConformanceScenario("vci_real_issuer_smoke")
    fun `strict sdk resolves offer and metadata against real issuer`() {
        val enabled = System.getenv("WPB_REAL_ISSUER_ENABLED")?.equals("true", ignoreCase = true) == true
        val offerUri = System.getenv("WPB_REAL_ISSUER_OFFER_URI")?.trim().orEmpty()

        assumeTrue(enabled, "real issuer test disabled (WPB_REAL_ISSUER_ENABLED != true)")
        assumeTrue(offerUri.isNotBlank(), "missing WPB_REAL_ISSUER_OFFER_URI")

        val properties = OpenId4VciProperties().apply {
            demoMode = false
            sdk.strictResolution = true
        }
        val signer = object : ProofJwtSigner {
            override fun sign(proof: ProofMaterial, audience: String, cNonce: String?): String {
                return "eyJhbGciOiJFUzI1NiJ9.eyJhdWQiOiIkaudienceIiwiY25vbmNlIjoi${cNonce ?: ""}In0.signature"
            }
        }
        val gateway = SdkOpenId4VciGateway(properties, signer)

        val (offer, metadata) = gateway.resolveOffer(offerUri)

        assertTrue(offer.credentialIssuerId.isNotBlank())
        assertTrue(metadata.credentialIssuerId.isNotBlank())
        assertTrue(metadata.credentialConfigurations.isNotEmpty())
        assertTrue(metadata.authorizationServers.isNotEmpty())
    }
}
