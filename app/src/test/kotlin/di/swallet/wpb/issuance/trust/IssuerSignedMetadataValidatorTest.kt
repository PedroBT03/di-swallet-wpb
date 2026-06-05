package di.swallet.wpb.issuance.trust

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

class IssuerSignedMetadataValidatorTest {

    private fun validator(policy: String) = IssuerSignedMetadataValidator(
        OpenId4VciProperties().apply { sdk.metadataPolicy = policy },
    )

    @Test
    fun `preferSigned accepts unsigned metadata`() {
        val result = validator("preferSigned").validate(
            ResolvedIssuerMetadata(credentialIssuerId = "https://issuer.example"),
        )
        assertTrue(result.acceptable)
        assertTrue(result.reason!!.contains("preferSigned"))
    }

    @Test
    fun `requireSigned rejects unsigned metadata`() {
        val result = validator("requireSigned").validate(
            ResolvedIssuerMetadata(credentialIssuerId = "https://issuer.example"),
        )
        assertFalse(result.acceptable)
    }

    @Test
    fun `ignoreSigned skips validation even when jwt is invalid`() {
        val result = validator("ignoreSigned").validate(
            ResolvedIssuerMetadata(
                credentialIssuerId = "https://issuer.example",
                signedMetadataJwt = "not.a.jwt",
            ),
        )
        assertTrue(result.acceptable)
    }

    @Test
    fun `valid signed metadata is accepted`() {
        val issuer = "https://issuer.example"
        val signing = SignedIssuerMetadataTestSupport.generateIssuerSigningMaterial()
        val jwt = SignedIssuerMetadataTestSupport.signedMetadataJwt(issuer, signing)
        val result = validator("requireSigned").validate(
            ResolvedIssuerMetadata(
                credentialIssuerId = issuer,
                signedMetadataPresent = true,
                signedMetadataJwt = jwt,
            ),
        )
        assertTrue(result.acceptable)
    }

    @Test
    fun `signed metadata with issuer mismatch is rejected`() {
        val signing = SignedIssuerMetadataTestSupport.generateIssuerSigningMaterial()
        val jwt = SignedIssuerMetadataTestSupport.signedMetadataJwt("https://other.example", signing)
        val result = validator("requireSigned").validate(
            ResolvedIssuerMetadata(
                credentialIssuerId = "https://issuer.example",
                signedMetadataJwt = jwt,
            ),
        )
        assertFalse(result.acceptable)
        assertTrue(result.reason!!.contains("does not match"))
    }

    @Test
    fun `expired signed metadata is rejected`() {
        val issuer = "https://issuer.example"
        val signing = SignedIssuerMetadataTestSupport.generateIssuerSigningMaterial()
        val jwt = SignedIssuerMetadataTestSupport.signedMetadataJwt(issuer, signing) { builder ->
            builder.expirationTime(Date.from(Instant.now().minusSeconds(600)))
        }
        val result = validator("requireSigned").validate(
            ResolvedIssuerMetadata(credentialIssuerId = issuer, signedMetadataJwt = jwt),
        )
        assertFalse(result.acceptable)
        assertTrue(result.reason!!.contains("expired"))
    }
}
