/**
 * Builds and parses W3C WebAuthn authenticator payloads for server-side pseudonym passkeys.
 */

package di.swallet.wpb.pseudonym

import com.authlete.cbor.CBORByteArray
import com.authlete.cbor.CBORItem
import com.authlete.cbor.CBORPair
import com.authlete.cbor.CBORPairList
import com.authlete.cbor.CBORString
import di.swallet.wpb.format.mdoc.MdocCoseKeyMaterial
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Encodes WebAuthn authData and attestation objects and decodes clientDataJSON for pseudonym ceremonies.
 */
object PseudonymWebAuthnCodec {
    private val zeroAaguid = ByteArray(16)

    /**
     * Computes the SHA-256 hash of a relying party ID as required by WebAuthn authData.
     */
    fun rpIdHash(rpId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))

    /**
     * Assembles registration authenticator data including attested credential and COSE public key.
     */
    fun buildRegistrationAuthData(
        rpId: String,
        credentialId: ByteArray,
        publicKey: ECPublicKey,
        signCount: Long = 0,
    ): ByteArray {
        val coseKey = MdocCoseKeyMaterial.toCoseEc2PublicKey(publicKey).encode()
        val flags = 0x45.toByte() // UP + UV + AT
        return buildAuthData(
            rpIdHash = rpIdHash(rpId),
            flags = flags,
            signCount = signCount,
            includeAttestedCredentialData = true,
            credentialId = credentialId,
            credentialPublicKey = coseKey,
        )
    }

    /**
     * Assembles assertion authenticator data with updated sign counter for authentication responses.
     */
    fun buildAssertionAuthData(
        rpId: String,
        signCount: Long,
    ): ByteArray {
        val flags = 0x05.toByte() // UP + UV
        return buildAuthData(
            rpIdHash = rpIdHash(rpId),
            flags = flags,
            signCount = signCount,
            includeAttestedCredentialData = false,
            credentialId = null,
            credentialPublicKey = null,
        )
    }

    /**
     * Wraps authData in a CBOR attestation object using the `none` attestation format.
     */
    fun buildAttestationObject(authData: ByteArray): ByteArray {
        val attestation = CBORPairList(
            CBORPair(CBORString("fmt"), CBORString("none")),
            CBORPair(CBORString("attStmt"), CBORPairList(emptyList<CBORPair>())),
            CBORPair(CBORString("authData"), CBORByteArray(authData)),
        )
        return attestation.encode()
    }

    /**
     * Builds a minimal clientDataJSON string for a WebAuthn ceremony type and challenge.
     */
    fun buildClientDataJson(type: String, challenge: String, origin: String): String =
        """{"type":"$type","challenge":"$challenge","origin":"$origin"}"""

    /**
     * Computes the SHA-256 hash of clientDataJSON used in assertion signature input.
     */
    fun clientDataHash(clientDataJson: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray(Charsets.UTF_8))

    /**
     * Concatenates authData and the clientDataJSON hash to form the bytes signed during authentication.
     */
    fun assertionSignatureInput(authData: ByteArray, clientDataJson: String): ByteArray =
        authData + clientDataHash(clientDataJson)

    /**
     * Parses Base64URL-encoded clientDataJSON and extracts challenge, origin, and ceremony type.
     */
    fun decodeClientData(clientDataJSON: String): Map<String, String> {
        val json = String(Base64.getUrlDecoder().decode(clientDataJSON.trim()))
        val challenge = """"challenge":"([^"]+)"""".toRegex().find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("clientDataJSON missing challenge")
        val origin = """"origin":"([^"]+)"""".toRegex().find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("clientDataJSON missing origin")
        val type = """"type":"([^"]+)"""".toRegex().find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("clientDataJSON missing type")
        return mapOf("challenge" to challenge, "origin" to origin, "type" to type)
    }

    /**
     * Serializes the binary WebAuthn authenticator data structure from its component fields.
     */
    private fun buildAuthData(
        rpIdHash: ByteArray,
        flags: Byte,
        signCount: Long,
        includeAttestedCredentialData: Boolean,
        credentialId: ByteArray?,
        credentialPublicKey: ByteArray?,
    ): ByteArray {
        val attested = if (includeAttestedCredentialData) {
            require(credentialId != null && credentialPublicKey != null)
            val credIdLen = byteArrayOf(
                ((credentialId.size shr 8) and 0xff).toByte(),
                (credentialId.size and 0xff).toByte(),
            )
            zeroAaguid + credIdLen + credentialId + credentialPublicKey
        } else {
            ByteArray(0)
        }
        val counter = byteArrayOf(
            ((signCount shr 24) and 0xff).toByte(),
            ((signCount shr 16) and 0xff).toByte(),
            ((signCount shr 8) and 0xff).toByte(),
            (signCount and 0xff).toByte(),
        )
        return rpIdHash + byteArrayOf(flags) + counter + attested
    }
}
