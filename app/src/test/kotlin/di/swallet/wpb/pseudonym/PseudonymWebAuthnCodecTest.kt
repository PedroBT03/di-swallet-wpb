package di.swallet.wpb.pseudonym

import di.swallet.wpb.security.Fido2TestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class PseudonymWebAuthnCodecTest {
    @Test
    fun `registration attestation object uses none format`() {
        val keyPair = Fido2TestHelper.generateDeviceKeyPair()
        val credentialId = ByteArray(32) { it.toByte() }
        val authData = PseudonymWebAuthnCodec.buildRegistrationAuthData(
            rpId = "rp.example.com",
            credentialId = credentialId,
            publicKey = keyPair.public as java.security.interfaces.ECPublicKey,
        )
        val attestationBytes = PseudonymWebAuthnCodec.buildAttestationObject(authData)
        val attestationText = String(attestationBytes)
        assertTrue(authData.size > 37)
        assertTrue(attestationBytes.isNotEmpty())
        assertTrue(attestationText.contains("none"))
    }

    @Test
    fun `client data roundtrip`() {
        val challenge = "abc123"
        val json = PseudonymWebAuthnCodec.buildClientDataJson("webauthn.create", challenge, "https://rp.example.com")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val parsed = PseudonymWebAuthnCodec.decodeClientData(encoded)
        assertEquals(challenge, parsed["challenge"])
        assertEquals("webauthn.create", parsed["type"])
    }
}
