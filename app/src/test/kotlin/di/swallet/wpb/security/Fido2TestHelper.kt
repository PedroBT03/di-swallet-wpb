/**
 * Helpers to generate device keys and WebAuthn assertions in tests.
 */

package di.swallet.wpb.security

import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.*
import java.math.BigInteger

object Fido2TestHelper {

    /**
     * Generates a fresh P-256 EC key pair matching the curve used by WebAuthn device
     * registration and assertion signing in integration tests.
     */
    fun generateDeviceKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Crafts a minimal WebAuthn assertion map (clientDataJSON, authenticatorData, signature)
     * signed with the device key over authData plus SHA-256 of client data for server verification.
     */
    fun createWebAuthnAssertion(
        userId: String,
        credentialId: String,
        challenge: String,
        deviceKeyPair: KeyPair
    ): Map<String, String> {
        
        val clientDataJsonRaw = """{"type":"webauthn.get","challenge":"$challenge","origin":"http://localhost"}"""
        val clientDataJSON = Base64.getUrlEncoder().withoutPadding().encodeToString(clientDataJsonRaw.toByteArray())

        // Craft Authenticator Data
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest("localhost".toByteArray())
        val flags = 0x05.toByte() // User Presence (0x01) + User Verification (0x04)
        val counter = byteArrayOf(0, 0, 0, 1)
        
        val authDataRaw = ByteArray(37)
        System.arraycopy(rpIdHash, 0, authDataRaw, 0, 32)
        authDataRaw[32] = flags
        System.arraycopy(counter, 0, authDataRaw, 33, 4)
        
        val authenticatorData = Base64.getUrlEncoder().withoutPadding().encodeToString(authDataRaw)

        // Compute Signature over (authData + SHA256(clientData))
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataJsonRaw.toByteArray())
        val signatureInput = authDataRaw + clientDataHash
        
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(deviceKeyPair.private)
        sig.update(signatureInput)
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign())

        return mapOf(
            "userId" to userId,
            "id" to credentialId,
            "clientDataJSON" to clientDataJSON,
            "authenticatorData" to authenticatorData,
            "signature" to signature
        )
    }

    /**
     * Encodes the EC public key as a base64url COSE_Key (ES256 / P-256) suitable for
     * Yubico WebAuthn device registration query parameters.
     */
    fun getPublicKeyBase64(keyPair: KeyPair): String {
        // Convert EC public key to COSE format (what Yubico expects)
        val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val point = ecPublicKey.w

        // Extract X and Y coordinates, padded to 32 bytes each
        /** Normalises a BigInteger coordinate to exactly 32 bytes for COSE encoding. */
        fun BigInteger.toBytes32(): ByteArray {
            val bytes = this.toByteArray()
            return when {
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33) // strip sign byte
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes               // left-pad
                else -> bytes
            }
        }

        val x = point.affineX.toBytes32()
        val y = point.affineY.toBytes32()

        // Build COSE_Key map for EC2 (P-256)
        // CBOR encoding of: {1: 2, 3: -7, -1: 1, -2: x, -3: y}
        val cose = buildCoseKey(x, y)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cose)
    }

    /** Manually CBOR-encodes a five-field COSE EC2 public key from padded X and Y coordinates. */
    private fun buildCoseKey(x: ByteArray, y: ByteArray): ByteArray {
        // Manual CBOR encoding of COSE EC2 key
        val out = java.io.ByteArrayOutputStream()
        out.write(0xa5)              // map(5)
        out.write(0x01); out.write(0x02)              // kty: EC2
        out.write(0x03); out.write(0x26)              // alg: ES256 (-7)
        out.write(0x20); out.write(0x01)              // crv: P-256
        out.write(0x21); out.write(0x58); out.write(0x20); out.write(x)  // x
        out.write(0x22); out.write(0x58); out.write(0x20); out.write(y)  // y
        return out.toByteArray()
    }
}
