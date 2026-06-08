package di.swallet.wpb.pseudonym

import com.nimbusds.jose.crypto.impl.ECDSA
import di.swallet.wpb.security.Fido2TestHelper
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

class PseudonymAssertionVerificationTest {
    @Test
    fun `assertion signature verifies with rp public key`() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val keyPair = Fido2TestHelper.generateDeviceKeyPair()
        val rpId = "rp.example.com"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val origin = "https://$rpId"
        val clientDataJson = PseudonymWebAuthnCodec.buildClientDataJson("webauthn.get", challenge, origin)
        val authData = PseudonymWebAuthnCodec.buildAssertionAuthData(rpId, signCount = 1)
        val signatureInput = PseudonymWebAuthnCodec.assertionSignatureInput(authData, clientDataJson)

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(signatureInput)
        val derSignature = sig.sign()
        val rawSignature = ECDSA.transcodeSignatureToConcat(derSignature, 64)

        val verifier = Signature.getInstance("SHA256withECDSA", BouncyCastleProvider.PROVIDER_NAME)
        verifier.initVerify(keyPair.public as ECPublicKey)
        verifier.update(signatureInput)
        assertTrue(verifier.verify(derSignature))
        assertEquals(64, rawSignature.size)
    }
}
