package di.swallet.wpb.issuance.trust

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultIssuerTrustValidatorTest {

    private fun validator(props: OpenId4VciProperties) =
        DefaultIssuerTrustValidator(props, IssuerSignedMetadataValidator(props))

    @Test
    fun `empty allow-list in demo-mode is permissive`() {
        val props = OpenId4VciProperties().apply {
            demoMode = true
            trust.allowedIssuerIds = ""
        }
        val decision = validator(props).validate(
            ResolvedIssuerMetadata(credentialIssuerId = "https://anything.example"),
        )
        assertTrue(decision.trusted)
    }

    @Test
    fun `empty allow-list outside demo-mode is fail-closed`() {
        val props = OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = ""
        }
        val decision = validator(props).validate(
            ResolvedIssuerMetadata(credentialIssuerId = "https://anything.example"),
        )
        assertFalse(decision.trusted)
    }

    @Test
    fun `allow-list accepts listed issuers`() {
        val props = OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = "https://issuer.one,https://issuer.two"
        }
        val decision = validator(props).validate(
            ResolvedIssuerMetadata(credentialIssuerId = "https://issuer.one"),
        )
        assertTrue(decision.trusted)
    }

    @Test
    fun `allow-list rejects unknown issuers`() {
        val props = OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = "https://issuer.one"
        }
        val decision = validator(props).validate(
            ResolvedIssuerMetadata(credentialIssuerId = "https://unknown.example"),
        )
        assertFalse(decision.trusted)
        assertTrue(decision.reason!!.contains("not in allow-list"))
    }

    @Test
    fun `blank issuer identifier is rejected`() {
        val props = OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = "https://issuer.one"
        }
        val decision = validator(props).validate(
            ResolvedIssuerMetadata(credentialIssuerId = ""),
        )
        assertFalse(decision.trusted)
    }

    @Test
    fun `requireSigned rejects allow-listed issuer without signed metadata`() {
        val issuer = "https://issuer.one"
        val props = OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = issuer
            sdk.metadataPolicy = "requireSigned"
        }
        val decision = validator(props).validate(
            ResolvedIssuerMetadata(credentialIssuerId = issuer),
        )
        assertFalse(decision.trusted)
        assertTrue(decision.reason!!.contains("signed_metadata"))
    }

    @Test
    fun `requireSigned accepts allow-listed issuer with valid signed metadata`() {
        val issuer = "https://issuer.one"
        val signing = SignedIssuerMetadataTestSupport.generateIssuerSigningMaterial()
        val jwt = SignedIssuerMetadataTestSupport.signedMetadataJwt(issuer, signing)
        val props = OpenId4VciProperties().apply {
            demoMode = false
            trust.allowedIssuerIds = issuer
            sdk.metadataPolicy = "requireSigned"
        }
        val decision = validator(props).validate(
            ResolvedIssuerMetadata(
                credentialIssuerId = issuer,
                signedMetadataPresent = true,
                signedMetadataJwt = jwt,
            ),
        )
        assertTrue(decision.trusted)
    }
}
