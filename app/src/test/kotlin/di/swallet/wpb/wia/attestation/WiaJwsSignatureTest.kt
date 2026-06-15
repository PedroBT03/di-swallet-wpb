/**
 * Tests wia jws signature.
 */

package di.swallet.wpb.wia.attestation

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.service.HsmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID

class WiaJwsSignatureTest : BaseIntegrationTest() {

    @Autowired
    lateinit var provider: DefaultWalletAttestationProvider

    @Autowired
    lateinit var hsmService: HsmService

    /**
     * Issued WIA JWT verifies with x5c leaf key; cnfJkt matches wallet public key thumbprint and tampered payload fails verification.
     */
    @Test
    fun `wallet instance attestation JWS verifies with x5c leaf certificate`() {
        val holderId = "wia-signature-${UUID.randomUUID()}"
        val walletKey = hsmService.generateKeyForUser(holderId)

        val attestation = provider.issue(
            holderId = holderId,
            walletInstanceId = holderId,
            issuerId = "https://issuer.example",
        )

        val parsed = SignedJWT.parse(attestation.jwt)
        assertNotNull(parsed.header.x509CertChain)
        val leafCertB64 = parsed.header.x509CertChain!!.first().toString()
        val cert = parseCertificate(leafCertB64)
        assertTrue(parsed.verify(ECDSAVerifier(cert.publicKey as ECPublicKey)))

        assertEquals(
            Rfc7638JwkThumbprint.fromPublicKeyBase64(walletKey.publicKeyBase64),
            attestation.cnfJkt,
        )

        val jwtParts = attestation.jwt.split('.').toMutableList()
        val payloadBytes = Base64.getUrlDecoder().decode(jwtParts[1])
        payloadBytes[0] = (payloadBytes[0].toInt() xor 0x01).toByte()
        jwtParts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
        val tampered = SignedJWT.parse(jwtParts.joinToString("."))
        assertFalse(tampered.verify(ECDSAVerifier(cert.publicKey as ECPublicKey)))
    }

    /** Decodes a standard Base64 DER certificate string into an X509Certificate for JWS verification. */
    private fun parseCertificate(base64Der: String): X509Certificate {
        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64Der))) as X509Certificate
    }
}
