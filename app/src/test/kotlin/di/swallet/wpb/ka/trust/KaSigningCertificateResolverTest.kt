/**
 * Tests ka signing certificate resolver.
 */

package di.swallet.wpb.ka.trust

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.service.HsmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.io.DefaultResourceLoader
import java.time.LocalDateTime

class KaSigningCertificateResolverTest {

    private val validator = CertificateChainValidator(DefaultResourceLoader())

    /**
     * ka.signingX5c configured takes precedence over HSM chain when demoMode is false.
     */
    @Test
    fun `configured signing chain takes precedence`() {
        val certB64 = testCertBase64()
        val props = OpenId4VciProperties().apply {
            demoMode = false
            ka.signingX5c = certB64
        }
        val hsm = Mockito.mock(HsmService::class.java)
        val resolver = KaSigningCertificateResolver(props, hsm, validator)
        val chain = resolver.resolveSigningChain(walletKey())
        assertEquals(listOf(certB64), chain)
    }

    /**
     * demoMode without configured signingX5c falls back to HSM certificateChainBase64.
     */
    @Test
    fun `demo mode falls back to hsm certificate chain`() {
        val certB64 = testCertBase64()
        val props = OpenId4VciProperties().apply { demoMode = true }
        val hsm = Mockito.mock(HsmService::class.java)
        val key = walletKey()
        Mockito.`when`(hsm.certificateChainBase64(key)).thenReturn(listOf(certB64))
        val resolver = KaSigningCertificateResolver(props, hsm, validator)
        val chain = resolver.resolveSigningChain(key)
        assertEquals(listOf(certB64), chain)
    }

    /**
     * requireConfiguredSigningChain=true with no ka.signingX5c throws IllegalStateException on resolve.
     */
    @Test
    fun `require configured signing chain fails without configured x5c`() {
        val props = OpenId4VciProperties().apply { ka.requireConfiguredSigningChain = true }
        val hsm = Mockito.mock(HsmService::class.java)
        val resolver = KaSigningCertificateResolver(props, hsm, validator)
        assertThrows(IllegalStateException::class.java) {
            resolver.resolveSigningChain(walletKey())
        }
    }

    /** Minimal WalletKey stub used when exercising KaSigningCertificateResolver chain resolution. */
    private fun walletKey() = WalletKey(
        userId = "holder-1",
        keyAlias = "key-holder-1-1",
        publicKeyBase64 = "abc",
        revocationIndex = 1,
        createdAt = LocalDateTime.now(),
    )

    /** Generates a self-signed EC certificate and returns its base64 encoding for resolver tests. */
    private fun testCertBase64(): String {
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val subject = org.bouncycastle.asn1.x500.X500Name("CN=KA-Resolver-Test")
        val now = java.util.Date()
        val certBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            subject,
            java.math.BigInteger.valueOf(42),
            now,
            java.util.Date(now.time + 3600_000),
            subject,
            kp.public,
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
        return java.util.Base64.getEncoder().encodeToString(cert.encoded)
    }
}
