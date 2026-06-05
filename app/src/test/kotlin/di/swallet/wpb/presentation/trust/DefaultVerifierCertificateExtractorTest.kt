package di.swallet.wpb.presentation.trust

import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationRequirements
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class DefaultVerifierCertificateExtractorTest {
    private val extractor = DefaultVerifierCertificateExtractor()

    @Test
    fun `extracts x5c from top-level verifier_info shape`() {
        val chain = TrustTestCertificates.issueChain()
        val leafB64 = Base64.getEncoder().encodeToString(chain.leaf.encoded)
        val request = resolvedRequest("""{"x5c":["$leafB64"]}""")
        val material = extractor.extract(request)
        assertNotNull(material)
        assertEquals(1, material!!.chain.size)
        assertEquals(TrustTestCertificates.sha256Hex(chain.leaf), TrustTestCertificates.sha256Hex(material.leaf))
    }

    @Test
    fun `extracts x5c from nested access_certificate object`() {
        val chain = TrustTestCertificates.issueChain()
        val leafB64 = Base64.getEncoder().encodeToString(chain.leaf.encoded)
        val request = resolvedRequest("""{"access_certificate":{"x5c":["$leafB64"]}}""")
        val material = extractor.extract(request)
        assertNotNull(material)
        assertTrue(material!!.chain.isNotEmpty())
    }

    @Test
    fun `extracts PEM bundle from certificatePem field`() {
        val chain = TrustTestCertificates.issueChain()
        val pem = TrustTestCertificates.pem(chain.leaf)
        val request = resolvedRequest("""{"certificatePem":${TrustTestCertificates.jsonString(pem)}}""")
        val material = extractor.extract(request)
        assertNotNull(material)
        assertEquals(1, material!!.chain.size)
    }

    @Test
    fun `returns null when verifier info is blank`() {
        val request = resolvedRequest(null)
        assertNull(extractor.extract(request))
    }

    private fun resolvedRequest(verifierInfoJson: String?): ResolvedAuthorizationRequest =
        ResolvedAuthorizationRequest(
            requestToken = "rt",
            requestUri = "http://verifier/req",
            clientId = "verifier-demo-client",
            responseMode = PresentationResponseMode.DIRECT_POST,
            nonce = "n",
            state = "s",
            requirements = PresentationRequirements(dcqlQueryJson = "{}", credentialQueryIds = emptyList()),
            verifierInfoJson = verifierInfoJson,
        )
}
