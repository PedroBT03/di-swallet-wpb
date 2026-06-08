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
 * Builds W3C WebAuthn authenticator payloads (authData, attestationObject) for server-side passkeys.
 * Attestation format is `none` (software/server-side authenticator MVP).
 */
object PseudonymWebAuthnCodec {
    private val zeroAaguid = ByteArray(16)

    fun rpIdHash(rpId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))

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

    fun buildAttestationObject(authData: ByteArray): ByteArray {
        val attestation = CBORPairList(
            CBORPair(CBORString("fmt"), CBORString("none")),
            CBORPair(CBORString("attStmt"), CBORPairList(emptyList<CBORPair>())),
            CBORPair(CBORString("authData"), CBORByteArray(authData)),
        )
        return attestation.encode()
    }

    fun buildClientDataJson(type: String, challenge: String, origin: String): String =
        """{"type":"$type","challenge":"$challenge","origin":"$origin"}"""

    fun clientDataHash(clientDataJson: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray(Charsets.UTF_8))

    fun assertionSignatureInput(authData: ByteArray, clientDataJson: String): ByteArray =
        authData + clientDataHash(clientDataJson)

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
