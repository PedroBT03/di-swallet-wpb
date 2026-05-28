package di.swallet.wpb.ka.attestation

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.service.HsmService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class KaJwsSignatureTest : BaseIntegrationTest() {

    @Autowired
    lateinit var provider: DefaultKeyAttestationProvider

    @Autowired
    lateinit var hsmService: HsmService

    @Test
    fun `key attestation JWS verifies with x5c leaf certificate`() {
        val holderId = "ka-signature-${UUID.randomUUID()}"
        hsmService.generateKeyForUser(holderId)
        val proofPublic = proofKey()
        val metadata = ResolvedIssuerMetadata(credentialIssuerId = "https://issuer.example")
        val config = CredentialConfigurationDescriptor(
            id = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            keyAttestationRequired = true,
            proofTypesSupported = listOf("attestation"),
        )
        val attestation = provider.issue(
            holderId = holderId,
            issuerId = metadata.credentialIssuerId,
            metadata = metadata,
            configuration = config,
            proofPublicKey = proofPublic,
            proofKeyId = "proof-key-1",
        )

        val parsed = SignedJWT.parse(attestation.jwt)
        val leafCertB64 = parsed.header.x509CertChain!!.first().toString()
        val cert = parseCertificate(leafCertB64)

        assertTrue(parsed.verify(ECDSAVerifier(cert.publicKey as ECPublicKey)))

        val jwtParts = attestation.jwt.split('.').toMutableList()
        val payloadBytes = Base64.getUrlDecoder().decode(jwtParts[1])
        payloadBytes[0] = (payloadBytes[0].toInt() xor 0x01).toByte()
        jwtParts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
        val tampered = SignedJWT.parse(jwtParts.joinToString("."))
        assertFalse(tampered.verify(ECDSAVerifier(cert.publicKey as ECPublicKey)))
    }

    private fun parseCertificate(base64Der: String): X509Certificate {
        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64Der))) as X509Certificate
    }

    private fun proofKey(): ECPublicKey {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return kp.public as ECPublicKey
    }
}
