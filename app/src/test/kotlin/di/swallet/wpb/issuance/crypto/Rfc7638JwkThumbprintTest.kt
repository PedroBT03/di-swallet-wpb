package di.swallet.wpb.issuance.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class Rfc7638JwkThumbprintTest {

    @Test
    fun `thumbprint excludes alg and kid members`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = keyPair.public as java.security.interfaces.ECPublicKey

        val thumbprint = Rfc7638JwkThumbprint.fromEcPublicKey(publicKey)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val legacyDigest = encoder.encodeToString(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("""{"alg":"ES256","crv":"P-256","kid":"kid-1","kty":"EC","x":"abc","y":"def"}""".toByteArray()),
        )
        assertNotEquals(legacyDigest, thumbprint)
        assertNotEquals(
            encoder.encodeToString(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("alias:pub".toByteArray()),
            ),
            thumbprint,
        )
    }

    @Test
    fun `fromPublicKeyBase64 matches fromEcPublicKey`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded)

        assertEquals(
            Rfc7638JwkThumbprint.fromEcPublicKey(publicKey),
            Rfc7638JwkThumbprint.fromPublicKeyBase64(encoded),
        )
    }
}
