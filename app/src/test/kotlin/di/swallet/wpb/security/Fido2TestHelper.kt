package di.swallet.wpb.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.*

/**
 * Helper class that simulates a User's Mobile Device (FIDO2 Authenticator).
 * It generates real Elliptic Curve keys and signs challenges.
 */
object Fido2TestHelper {

    /**
     * Generates a new EC KeyPair simulating a Secure Enclave key generation.
     */
    fun generateDeviceKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Signs a challenge using the device's private key.
     */
    fun signChallenge(privateKey: java.security.PrivateKey, challenge: String): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(challenge.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign())
    }
    
    /**
     * Returns the Base64 representation of the Public Key for registration.
     */
    fun getPublicKeyBase64(keyPair: KeyPair): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded)
    }
}