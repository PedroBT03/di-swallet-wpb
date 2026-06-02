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
    fun decodeIssuerSigned(rawB64Url: String): CBORObject = parseCborB64(rawB64Url)

    fun decodeDeviceResponse(rawB64Url: String): CBORObject = parseCborB64(rawB64Url)

    fun verifyIssuerAuth(rawIssuerSignedB64Url: String): Boolean {
        val issuerSigned = decodeIssuerSigned(rawIssuerSignedB64Url)
        val issuerAuth = issuerSigned.get("issuerAuth") ?: return false
        val sign1 = decodeSign1(issuerAuth) ?: return false
        val verifierKey = issuerVerifierKey(sign1) ?: return false
        return sign1.validate(verifierKey)
    }

    fun debugIssuerAuth(rawIssuerSignedB64Url: String): String {
        val issuerSigned = decodeIssuerSigned(rawIssuerSignedB64Url)
        val issuerAuth = issuerSigned.get("issuerAuth") ?: return "issuerAuth missing"
        val sign1 = decodeSign1(issuerAuth) ?: return "issuerAuth not cose sign1"
        val key = issuerVerifierKey(sign1)
        val payload = sign1.GetContent()
        return "issuerAuthDecoded=true key=${key != null} payload=${payload?.size ?: -1}"
    }

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

    fun extractIssuerAuthPayload(rawIssuerSignedB64Url: String): ByteArray {
        val issuerSigned = decodeIssuerSigned(rawIssuerSignedB64Url)
        val issuerAuth = issuerSigned.get("issuerAuth")
            ?: throw IllegalArgumentException("issuerAuth missing")
        val sign1 = decodeSign1(issuerAuth)
            ?: throw IllegalArgumentException("issuerAuth not decodeable as COSE_Sign1")
        return sign1.GetContent()
            ?: throw IllegalArgumentException("issuerAuth payload missing")
    }

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

    private fun parseCborB64(rawB64Url: String): CBORObject {
        val bytes = Base64.getUrlDecoder().decode(padBase64Url(rawB64Url))
        return CBORObject.DecodeFromBytes(bytes)
    }

    private fun padBase64Url(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)

    private fun unwrapEmbeddedCbor(value: CBORObject): CBORObject {
        val maybeBytes = when {
            value.getType() == com.upokecenter.cbor.CBORType.ByteString -> value.GetByteString()
            value.isTagged && value.getType() == com.upokecenter.cbor.CBORType.ByteString -> value.GetByteString()
            else -> return value
        }
        return runCatching { CBORObject.DecodeFromBytes(maybeBytes) }.getOrDefault(value)
    }

    private fun decodeSign1(obj: CBORObject): Sign1Message? {
        return runCatching {
            Message.DecodeFromBytes(obj.EncodeToBytes(), MessageTag.Sign1) as Sign1Message
        }.getOrNull()
    }

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
