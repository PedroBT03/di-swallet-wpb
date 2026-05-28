package di.swallet.wpb.ka.attestation

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.service.HsmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

class DefaultKeyAttestationProviderTest : BaseIntegrationTest() {

    @Autowired
    lateinit var provider: DefaultKeyAttestationProvider

    @Autowired
    lateinit var hsmService: HsmService

    @Test
    fun `issues signed key attestation with proof key binding`() {
        val holderId = "ka-holder-${UUID.randomUUID()}"
        val walletKey = hsmService.generateKeyForUser(holderId)
        val proof = proofKey()
        val metadata = ResolvedIssuerMetadata(credentialIssuerId = "https://issuer.example")
        val config = CredentialConfigurationDescriptor(
            id = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            keyAttestationRequired = true,
            proofTypesSupported = listOf("jwt", "attestation"),
        )

        val attestation = provider.issue(
            holderId = holderId,
            issuerId = metadata.credentialIssuerId,
            metadata = metadata,
            configuration = config,
            proofPublicKey = proof,
            proofKeyId = "proof-key-1",
        )

        val jwt = SignedJWT.parse(attestation.jwt)
        assertEquals("keyattestation+jwt", jwt.header.type?.type)
        assertEquals("ES256", jwt.header.algorithm.name)
        assertEquals(walletKey.keyAlias, jwt.header.keyID)
        assertFalse(jwt.header.x509CertChain.isNullOrEmpty())

        val walletPublicKey = decodeWalletPublicKey(walletKey.publicKeyBase64)
        assertTrue(jwt.verify(ECDSAVerifier(walletPublicKey)))

        val attestedKeys = jwt.jwtClaimsSet.getClaim("attested_keys") as List<*>
        val firstKey = attestedKeys.first() as Map<*, *>
        val jwk = firstKey["jwk"] as Map<*, *>
        assertEquals("proof-key-1", jwk["kid"])
    }

    private fun proofKey(): ECPublicKey {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return kp.public as ECPublicKey
    }

    private fun decodeWalletPublicKey(publicKeyBase64: String): ECPublicKey {
        val bytes = Base64.getUrlDecoder().decode(publicKeyBase64)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }
}
