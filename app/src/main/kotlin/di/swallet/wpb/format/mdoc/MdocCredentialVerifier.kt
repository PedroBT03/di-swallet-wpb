/**
 * COSE signature verification for mdoc IssuerSigned and DeviceResponse artifacts.
 */

package di.swallet.wpb.format.mdoc

import com.authlete.cbor.CBORDecoder
import com.authlete.cbor.CBORItem
import com.authlete.cbor.CBORItemList
import com.authlete.cbor.CBORPairList
import com.authlete.cbor.CBORString
import com.authlete.cose.COSEEC2Key
import com.authlete.cose.COSEKey
import com.authlete.cose.COSESign1
import com.authlete.cose.COSEVerifier
import org.springframework.stereotype.Component
import java.util.Base64

/** Verifies issuerAuth and device authentication COSE signatures on mdoc CBOR payloads. */
@Component
class MdocCredentialVerifier {
    /** Verifies the issuerAuth COSE signature on an IssuerSigned artifact. */
    fun validateIssuerSigned(raw: String): Boolean {
        val issuerAuth = extractIssuerAuthItem(raw) ?: return false
        val cose = runCatching { COSESign1.build(issuerAuth) }.getOrNull() ?: return false
        val issuerKey = extractIssuerPublicKey(cose) ?: return false
        return runCatching { COSEVerifier(issuerKey).verify(cose) }.getOrDefault(false)
    }

    /** Verifies issuerAuth and deviceSignature on a DeviceResponse artifact. */
    fun validateDeviceResponse(raw: String): Boolean {
        val root = decodeCborItem(raw) ?: return false
        val issuerSignedItem = extractIssuerSignedFromDeviceResponse(root) ?: return false
        if (!validateIssuerSignedItem(issuerSignedItem)) return false
        val deviceKey = extractDevicePublicKeyFromIssuerSigned(issuerSignedItem) ?: return false
        val devicePublicKey = runCatching { deviceKey.toECPublicKey() }.getOrNull() ?: return false
        val deviceSignature = extractDeviceSignature(root) ?: return false
        val deviceCose = runCatching { COSESign1.build(deviceSignature) }.getOrNull() ?: return false
        return runCatching { COSEVerifier(devicePublicKey).verify(deviceCose) }.getOrDefault(false)
    }

    /** Extracts the MSO device public key from an IssuerSigned base64url payload. */
    fun extractDeviceCosePublicKey(rawIssuerSignedB64: String): COSEEC2Key? {
        val issuerSignedItem = decodeCborItem(rawIssuerSignedB64) ?: return null
        return extractDevicePublicKeyFromIssuerSigned(issuerSignedItem)
    }

    /** Verifies issuerAuth on a parsed IssuerSigned CBOR item. */
    private fun validateIssuerSignedItem(issuerSignedItem: CBORItem): Boolean {
        val issuerAuth = readPairValue(issuerSignedItem, "issuerAuth") ?: return false
        val cose = runCatching { COSESign1.build(issuerAuth) }.getOrNull() ?: return false
        val issuerKey = extractIssuerPublicKey(cose) ?: return false
        return runCatching { COSEVerifier(issuerKey).verify(cose) }.getOrDefault(false)
    }

    /** Locates issuerAuth in IssuerSigned input or nested inside DeviceResponse. */
    private fun extractIssuerAuthItem(raw: String): CBORItem? {
        val root = decodeCborItem(raw) ?: return null
        readPairValue(root, "issuerAuth")?.let { return it }
        return extractIssuerSignedFromDeviceResponse(root)?.let { readPairValue(it, "issuerAuth") }
    }

    /** Reads issuerSigned from the first document in a DeviceResponse. */
    private fun extractIssuerSignedFromDeviceResponse(root: CBORItem): CBORItem? {
        val docs = readPairValue(root, "documents") as? CBORItemList ?: return null
        val firstDoc = docs.items.firstOrNull() as? CBORPairList ?: return null
        return readPairValue(firstDoc, "issuerSigned")
    }

    /** Reads deviceSignature from deviceAuth inside the first document. */
    private fun extractDeviceSignature(root: CBORItem): CBORItem? {
        val docs = readPairValue(root, "documents") as? CBORItemList ?: return null
        val firstDoc = docs.items.firstOrNull() as? CBORPairList ?: return null
        val deviceSigned = readPairValue(firstDoc, "deviceSigned") as? CBORPairList ?: return null
        val deviceAuth = readPairValue(deviceSigned, "deviceAuth") as? CBORPairList ?: return null
        return readPairValue(deviceAuth, "deviceSignature")
    }

    /** Resolves issuer verification key from x5c on the COSE protected or unprotected header. */
    private fun extractIssuerPublicKey(cose: COSESign1): java.security.PublicKey? {
        val chain = cose.unprotectedHeader?.getX5Chain()
            ?: cose.protectedHeader?.getX5Chain()
            ?: return null
        return chain.firstOrNull()?.publicKey
    }

    /** Parses the MSO from issuerAuth payload and reads the embedded deviceKey. */
    private fun extractDevicePublicKeyFromIssuerSigned(issuerSignedItem: CBORItem): COSEEC2Key? {
        val issuerAuth = readPairValue(issuerSignedItem, "issuerAuth") ?: return null
        val cose = runCatching { COSESign1.build(issuerAuth) }.getOrNull() ?: return null
        val mso = decodeMobileSecurityObject(cose.payload) ?: return null
        val deviceKeyInfo = readPairValue(mso, "deviceKeyInfo") ?: return null
        val deviceKeyNode = when (deviceKeyInfo) {
            is CBORPairList -> readPairValue(deviceKeyInfo, "deviceKey")
            else -> readPairValue(deviceKeyInfo, "deviceKey")
        } ?: return null
        val built = runCatching { COSEKey.build(deviceKeyNode) }.getOrNull() ?: return null
        return built as? COSEEC2Key
    }

    /** Unwraps tagged or byte-array MSO payloads into a CBOR map item. */
    private fun decodeMobileSecurityObject(payload: CBORItem?): CBORItem? {
        if (payload == null) return null
        if (payload is com.authlete.cbor.CBORByteArray) {
            payload.decodedContent?.firstOrNull()?.let { return unwrapEmbeddedCbor(it) }
            val decoded = runCatching { CBORDecoder(payload.value).next() }.getOrNull()
            return unwrapEmbeddedCbor(decoded)
        }
        return unwrapEmbeddedCbor(payload)
    }

    /** Recursively unwraps byte arrays and CBOR tags to reach the embedded item. */
    private fun unwrapEmbeddedCbor(item: CBORItem?): CBORItem? {
        if (item == null) return null
        when (item) {
            is com.authlete.cbor.CBORByteArray -> {
                item.decodedContent?.firstOrNull()?.let { return unwrapEmbeddedCbor(it) }
                val decoded = runCatching { CBORDecoder(item.value).next() }.getOrNull()
                return unwrapEmbeddedCbor(decoded)
            }
            is com.authlete.cbor.CBORTaggedItem -> return unwrapEmbeddedCbor(item.tagContent)
            else -> return item
        }
    }

    /** Decodes a base64url string into a top-level CBOR item. */
    private fun decodeCborItem(rawB64: String): CBORItem? {
        val bytes = decodeBase64Url(rawB64) ?: return null
        return runCatching { CBORDecoder(bytes).next() }.getOrNull()
    }

    /** Decodes base64url with optional padding restoration. */
    private fun decodeBase64Url(value: String): ByteArray? {
        val clean = value.trim()
        if (clean.isBlank()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
    }

    /** Reads a named value from a CBOR pair list. */
    private fun readPairValue(item: CBORItem, key: String): CBORItem? {
        val pairList = item as? CBORPairList ?: return null
        return pairList.pairs.firstOrNull { pair ->
            (pair.key as? CBORString)?.value == key
        }?.value
    }
}
