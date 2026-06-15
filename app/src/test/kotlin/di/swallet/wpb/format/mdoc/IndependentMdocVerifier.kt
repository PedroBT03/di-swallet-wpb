/**
 * Tests independent mdoc verifier.
 */

package di.swallet.wpb.format.mdoc

import COSE.Attribute
import COSE.Message
import COSE.MessageTag
import COSE.OneKey
import COSE.Sign1Message
import com.upokecenter.cbor.CBORObject
import java.security.cert.CertificateFactory
import java.util.Base64

/**
 * Independent verifier used only in tests.
 *
 * This parser/verifier does not use Authlete APIs for parsing/validation.
 * It uses cose-java + upokecenter CBOR to assert cross-implementation interoperability.
 */
class IndependentMdocVerifier {
    /** Decodes a base64url-encoded CBOR IssuerSigned structure into a CBORObject tree. */
    fun decodeIssuerSigned(rawB64Url: String): CBORObject = parseCborB64(rawB64Url)

    /** Decodes a base64url-encoded CBOR DeviceResponse into a CBORObject tree. */
    fun decodeDeviceResponse(rawB64Url: String): CBORObject = parseCborB64(rawB64Url)

    /** Validates issuerAuth COSE_Sign1 inside IssuerSigned using the x5c leaf certificate public key. */
    fun verifyIssuerAuth(rawIssuerSignedB64Url: String): Boolean {
        val issuerSigned = decodeIssuerSigned(rawIssuerSignedB64Url)
        val issuerAuth = issuerSigned.get("issuerAuth") ?: return false
        val sign1 = decodeSign1(issuerAuth) ?: return false
        val verifierKey = issuerVerifierKey(sign1) ?: return false
        return sign1.validate(verifierKey)
    }

    /** Returns a short diagnostic string describing issuerAuth decode success, key presence, and payload size. */
    fun debugIssuerAuth(rawIssuerSignedB64Url: String): String {
        val issuerSigned = decodeIssuerSigned(rawIssuerSignedB64Url)
        val issuerAuth = issuerSigned.get("issuerAuth") ?: return "issuerAuth missing"
        val sign1 = decodeSign1(issuerAuth) ?: return "issuerAuth not cose sign1"
        val key = issuerVerifierKey(sign1)
        val payload = sign1.GetContent()
        return "issuerAuthDecoded=true key=${key != null} payload=${payload?.size ?: -1}"
    }

    /** Verifies issuerAuth on the embedded issuerSigned document and the deviceSignature using the MSO device key. */
    fun verifyDeviceResponse(rawDeviceResponseB64Url: String): Boolean {
        val deviceResponse = decodeDeviceResponse(rawDeviceResponseB64Url)
        val document = deviceResponse.get("documents")?.get(0) ?: return false
        val issuerSigned = document.get("issuerSigned") ?: return false
        val issuerAuth = issuerSigned.get("issuerAuth") ?: return false
        val issuerSign1 = decodeSign1(issuerAuth) ?: return false
        val issuerKey = issuerVerifierKey(issuerSign1) ?: return false
        if (!issuerSign1.validate(issuerKey)) return false

        val issuerContent = issuerSign1.GetContent() ?: return false
        val msoDecoded = runCatching { CBORObject.DecodeFromBytes(issuerContent) }.getOrNull() ?: return false
        val mso = unwrapEmbeddedCbor(msoDecoded)
        val deviceKeyInfo = runCatching { mso.get("deviceKeyInfo") }.getOrNull() ?: return false
        val deviceKey = runCatching { deviceKeyInfo.get("deviceKey") }.getOrNull() ?: return false
        val deviceOneKey = runCatching { OneKey(deviceKey) }.getOrNull() ?: return false

        val deviceSignature = document.get("deviceSigned")?.get("deviceAuth")?.get("deviceSignature") ?: return false
        val deviceSign1 = decodeSign1(deviceSignature) ?: return false
        return deviceSign1.validate(deviceOneKey)
    }

    /** Extracts the raw MSO bytes from issuerAuth inside a standalone IssuerSigned artifact. */
    fun extractIssuerAuthPayload(rawIssuerSignedB64Url: String): ByteArray {
        val issuerSigned = decodeIssuerSigned(rawIssuerSignedB64Url)
        val issuerAuth = issuerSigned.get("issuerAuth")
            ?: throw IllegalArgumentException("issuerAuth missing")
        val sign1 = decodeSign1(issuerAuth)
            ?: throw IllegalArgumentException("issuerAuth not decodeable as COSE_Sign1")
        return sign1.GetContent()
            ?: throw IllegalArgumentException("issuerAuth payload missing")
    }

    /** Extracts the MSO payload from issuerAuth nested under documents[0].issuerSigned in a DeviceResponse. */
    fun extractIssuerAuthPayloadFromDeviceResponse(rawDeviceResponseB64Url: String): ByteArray {
        val deviceResponse = decodeDeviceResponse(rawDeviceResponseB64Url)
        val document = deviceResponse.get("documents")?.get(0)
            ?: throw IllegalArgumentException("documents missing")
        val issuerAuth = document.get("issuerSigned")?.get("issuerAuth")
            ?: throw IllegalArgumentException("issuerAuth missing in DeviceResponse")
        val sign1 = decodeSign1(issuerAuth)
            ?: throw IllegalArgumentException("issuerAuth in DeviceResponse is not COSE_Sign1")
        return sign1.GetContent()
            ?: throw IllegalArgumentException("issuerAuth payload missing in DeviceResponse")
    }

    /** Looks up a string claim value by namespace and element identifier inside IssuerSigned nameSpaces. */
    fun extractIssuerSignedClaim(
        rawIssuerSignedB64Url: String,
        namespace: String,
        claim: String,
    ): String? {
        val issuerSigned = decodeIssuerSigned(rawIssuerSignedB64Url)
        val namespaceItems = issuerSigned.get("nameSpaces")?.get(namespace) ?: return null
        namespaceItems.values.forEach { taggedItem ->
            val issuerSignedItem = when {
                taggedItem.isTagged && taggedItem.getType() == com.upokecenter.cbor.CBORType.ByteString ->
                    CBORObject.DecodeFromBytes(taggedItem.GetByteString())
                taggedItem.getType() == com.upokecenter.cbor.CBORType.ByteString ->
                    CBORObject.DecodeFromBytes(taggedItem.GetByteString())
                else -> taggedItem
            }
            if (issuerSignedItem.get("elementIdentifier")?.AsString() == claim) {
                return issuerSignedItem.get("elementValue")?.AsString()
            }
        }
        return null
    }

    /** Reads a presented claim string from deviceSigned.nameSpaces inside the first DeviceResponse document. */
    fun extractPresentedClaim(
        rawDeviceResponseB64Url: String,
        namespace: String,
        claim: String,
    ): String? {
        val deviceResponse = decodeDeviceResponse(rawDeviceResponseB64Url)
        val document = deviceResponse.get("documents")?.get(0) ?: return null
        val namespacesObj = document.get("deviceSigned")?.get("nameSpaces") ?: return null
        val namespaces = when {
            namespacesObj.isTagged && namespacesObj.getType() == com.upokecenter.cbor.CBORType.ByteString ->
                CBORObject.DecodeFromBytes(namespacesObj.GetByteString())
            namespacesObj.getType() == com.upokecenter.cbor.CBORType.ByteString ->
                CBORObject.DecodeFromBytes(namespacesObj.GetByteString())
            else -> namespacesObj
        }
        return namespaces.get(namespace)?.get(claim)?.AsString()
    }

    /** Base64url-decodes [rawB64Url] with padding correction and parses the bytes as CBOR. */
    private fun parseCborB64(rawB64Url: String): CBORObject {
        val bytes = Base64.getUrlDecoder().decode(padBase64Url(rawB64Url))
        return CBORObject.DecodeFromBytes(bytes)
    }

    /** Appends '=' padding so base64url strings whose length is not a multiple of four decode correctly. */
    private fun padBase64Url(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)

    /** If [value] wraps nested CBOR as a byte string or tagged byte string, decodes it; otherwise returns [value]. */
    private fun unwrapEmbeddedCbor(value: CBORObject): CBORObject {
        val maybeBytes = when {
            value.getType() == com.upokecenter.cbor.CBORType.ByteString -> value.GetByteString()
            value.isTagged && value.getType() == com.upokecenter.cbor.CBORType.ByteString -> value.GetByteString()
            else -> return value
        }
        return runCatching { CBORObject.DecodeFromBytes(maybeBytes) }.getOrDefault(value)
    }

    /** Attempts to decode [obj] as a COSE Sign1 message, returning null when the bytes are not Sign1. */
    private fun decodeSign1(obj: CBORObject): Sign1Message? {
        return runCatching {
            Message.DecodeFromBytes(obj.EncodeToBytes(), MessageTag.Sign1) as Sign1Message
        }.getOrNull()
    }

    /** Extracts the issuer verification OneKey from the x5c (33) unprotected or protected header attribute. */
    private fun issuerVerifierKey(sign1: Sign1Message): OneKey? {
        val x5 = sign1.findAttribute(CBORObject.FromObject(33), Attribute.UNPROTECTED)
            ?: sign1.findAttribute(CBORObject.FromObject(33), Attribute.PROTECTED)
            ?: return null
        val certBytes = when {
            x5.getType() == com.upokecenter.cbor.CBORType.ByteString -> x5.GetByteString()
            x5.getType() == com.upokecenter.cbor.CBORType.Array && x5.size() > 0 -> x5.get(0).GetByteString()
            else -> return null
        }
        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(certBytes.inputStream())
        return runCatching { OneKey(cert.publicKey, null) }.getOrNull()
    }
}
