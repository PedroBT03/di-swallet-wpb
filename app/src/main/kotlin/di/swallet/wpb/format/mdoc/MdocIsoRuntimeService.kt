package di.swallet.wpb.format.mdoc

import com.authlete.cbor.CBORByteArray
import com.authlete.cbor.CBORDecoder
import com.authlete.cbor.CBORItem
import com.authlete.cbor.CBORItemList
import com.authlete.cbor.CBORPair
import com.authlete.cbor.CBORPairList
import com.authlete.cbor.CBORString
import com.authlete.cose.COSEEC2Key
import com.authlete.cose.COSEProtectedHeader
import com.authlete.cose.COSESign1
import com.authlete.cose.COSESigner
import com.authlete.cose.COSEUnprotectedHeader
import com.authlete.cose.COSEVerifier
import com.authlete.cose.SigStructure
import com.authlete.cose.constants.COSEAlgorithms
import com.authlete.mdoc.DeviceAuth
import com.authlete.mdoc.DeviceNameSpaces
import com.authlete.mdoc.DeviceNameSpacesBytes
import com.authlete.mdoc.DeviceNameSpacesEntry
import com.authlete.mdoc.DeviceSigned
import com.authlete.mdoc.DeviceSignedItems
import com.authlete.mdoc.DeviceSignedItemsEntry
import com.authlete.mdoc.IssuerSigned
import com.authlete.mdoc.IssuerSignedBuilder
import com.authlete.mdoc.ValidityInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

@Component
class MdocIsoRuntimeService {
    private val initProvider = AtomicBoolean(false)
    private val issuerKeyPair: KeyPair = ecKeyPair()
    private val deviceKeyPair: KeyPair = ecKeyPair()
    private val issuerCertificate: X509Certificate = selfSignedCertificate(issuerKeyPair, "CN=WPB mdoc issuer")
    private val issuerCoseKey: COSEEC2Key = toCoseEc2Key(
        privateKey = issuerKeyPair.private as ECPrivateKey,
        publicKey = issuerKeyPair.public as ECPublicKey,
    )
    private val issuerPublicKey = issuerKeyPair.public
    private val devicePrivateKey = deviceKeyPair.private
    private val devicePublicCoseKey: COSEEC2Key = toCoseEc2PublicKey(deviceKeyPair.public as ECPublicKey)

    fun issueIssuerSigned(
        docType: String,
        namespaceClaims: Map<String, Map<String, Any?>>,
    ): String {
        val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
        val validityInfo = ValidityInfo(now, now, now.plusYears(1))
        val claims = namespaceClaims.mapValues { it.value as Any }.toMap()
        val issuerSigned: IssuerSigned = IssuerSignedBuilder()
            .setDocType(docType)
            .setClaims(claims)
            .setValidityInfo(validityInfo)
            .setDeviceKey(devicePublicCoseKey)
            .setIssuerKey(issuerCoseKey)
            .setIssuerCertChain(listOf(issuerCertificate))
            .build()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(issuerSigned.encode())
    }

    fun buildDeviceResponse(
        originalIssuedPayload: String?,
        docType: String,
        namespaceClaims: Map<String, Map<String, Any?>>,
        requestedClaims: List<String>,
        audience: String,
        nonce: String,
    ): String {
        val issuerSignedItem = when {
            !originalIssuedPayload.isNullOrBlank() ->
                extractIssuerSignedItem(originalIssuedPayload)
                    ?: throw IllegalArgumentException("Invalid previously issued mdoc artifact for DeviceResponse build.")
            else -> decodeCborItem(issueIssuerSigned(docType, namespaceClaims))
                ?: throw IllegalStateException("Unable to decode freshly issued IssuerSigned artifact.")
        }
        val filtered = if (requestedClaims.isEmpty()) {
            namespaceClaims
        } else {
            namespaceClaims.mapValues { (_, claims) ->
                claims.filterKeys { key -> requestedClaims.contains(key) }
            }.filterValues { it.isNotEmpty() }
        }
        val deviceNameSpaces = DeviceNameSpaces(
            filtered.entries.map { (namespace, claims) ->
                DeviceNameSpacesEntry(
                    namespace,
                    DeviceSignedItems(
                        claims.entries.map { (claimName, value) ->
                            DeviceSignedItemsEntry(claimName, value ?: "")
                        },
                    ),
                )
            },
        )
        val deviceNameSpacesBytes = DeviceNameSpacesBytes(deviceNameSpaces)
        val deviceAuthenticationBytes = buildDeviceAuthenticationPayload(
            docType = docType,
            audience = audience,
            nonce = nonce,
            deviceNameSpacesBytes = deviceNameSpacesBytes,
        )
        val protectedHeader = COSEProtectedHeader.build(mapOf(1 to COSEAlgorithms.ES256))
        val sigStructure = SigStructure(
            protectedHeader,
            CBORByteArray(ByteArray(0)),
            CBORByteArray(deviceAuthenticationBytes),
        )
        val signature = COSESigner(devicePrivateKey).sign(sigStructure, COSEAlgorithms.ES256)
        val coseSign1 = COSESign1(
            protectedHeader,
            COSEUnprotectedHeader.build(emptyMap()),
            CBORByteArray(deviceAuthenticationBytes),
            CBORByteArray(signature),
        )
        val deviceAuth = DeviceAuth(coseSign1)
        val deviceSigned = DeviceSigned(deviceNameSpacesBytes, deviceAuth)
        val document = CBORPairList(
            CBORPair(CBORString("docType"), CBORString(docType)),
            CBORPair(CBORString("issuerSigned"), issuerSignedItem),
            CBORPair(CBORString("deviceSigned"), deviceSigned),
        )
        val response = CBORPairList(
            CBORPair(CBORString("version"), CBORString("1.0")),
            CBORPair(CBORString("documents"), CBORItemList(document)),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(response.encode())
    }

    fun decode(raw: String): MdocCredentialDocument? {
        val bytes = decodeBase64Url(raw) ?: return null
        val parsed = decodeCbor(bytes) ?: return null
        val parsedMap = parsed as? Map<*, *> ?: return null
        return when {
            parsedMap.containsKey("documents") -> decodeDeviceResponseMap(parsedMap)
            parsedMap.containsKey("nameSpaces") && parsedMap.containsKey("issuerAuth") -> decodeIssuerSignedMap(parsedMap)
            else -> null
        }
    }

    fun isEncodedMdoc(raw: String): Boolean = decode(raw) != null

    fun validateIssuerSigned(raw: String): Boolean {
        val cborItem = decodeCborItem(raw) ?: return false
        val issuerAuthItem = readPairValue(cborItem, "issuerAuth") ?: return false
        val cose = runCatching { COSESign1.build(issuerAuthItem) }.getOrNull() ?: return false
        return runCatching { COSEVerifier(issuerPublicKey).verify(cose) }.getOrDefault(false)
    }

    fun validateDeviceResponse(raw: String): Boolean {
        val item = decodeCborItem(raw) ?: return false
        val docsItem = readPairValue(item, "documents") ?: return false
        val docs = docsItem as? com.authlete.cbor.CBORItemList ?: return false
        val firstDoc = docs.items.firstOrNull() as? com.authlete.cbor.CBORPairList ?: return false
        val issuerSignedItem = readPairValue(firstDoc, "issuerSigned") as? com.authlete.cbor.CBORPairList ?: return false
        val issuerAuth = readPairValue(issuerSignedItem, "issuerAuth") ?: return false
        val issuerOk = runCatching {
            val issuerCose = COSESign1.build(issuerAuth)
            COSEVerifier(issuerPublicKey).verify(issuerCose)
        }.getOrDefault(false)
        val deviceSignedItem = readPairValue(firstDoc, "deviceSigned") as? com.authlete.cbor.CBORPairList ?: return false
        val deviceAuthItem = readPairValue(deviceSignedItem, "deviceAuth") as? com.authlete.cbor.CBORPairList ?: return false
        val deviceSignatureItem = readPairValue(deviceAuthItem, "deviceSignature") ?: return false
        val deviceOk = runCatching {
            val deviceCose = COSESign1.build(deviceSignatureItem)
            COSEVerifier(deviceKeyPair.public).verify(deviceCose)
        }.getOrDefault(false)
        return issuerOk && deviceOk
    }

    private fun decodeDeviceResponseMap(parsedMap: Map<*, *>): MdocCredentialDocument? {
        val documents = parsedMap["documents"] as? List<*> ?: return null
        val firstDoc = documents.firstOrNull() as? Map<*, *> ?: return null
        val docType = firstDoc["docType"] as? String ?: return null
        val claims = mutableMapOf<String, Any?>()
        val deviceSigned = firstDoc["deviceSigned"] as? Map<*, *>
        val namespacesBytes = deviceSigned?.get("nameSpaces") as? ByteArray
        val namespaces = namespacesBytes?.let { decodeCbor(it) as? Map<*, *> } ?: emptyMap<Any, Any>()
        namespaces.forEach { (namespace, signedItemsAny) ->
            val signedItems = signedItemsAny as? Map<*, *> ?: return@forEach
            signedItems.forEach { (claim, value) ->
                claims["$namespace.$claim"] = value
            }
        }
        return MdocCredentialDocument(
            docType = docType,
            namespace = claims.keys.firstOrNull()?.substringBeforeLast('.') ?: docType,
            claims = claims,
        )
    }

    private fun decodeIssuerSignedMap(parsedMap: Map<*, *>): MdocCredentialDocument {
        val claims = mutableMapOf<String, Any?>()
        val namespaces = parsedMap["nameSpaces"] as? Map<*, *> ?: emptyMap<Any, Any>()
        namespaces.forEach { (namespace, itemListAny) ->
            val itemList = itemListAny as? List<*> ?: return@forEach
            itemList.forEach { item ->
                val issuerItemMap = when (item) {
                    is ByteArray -> decodeCbor(item) as? Map<*, *>
                    is Map<*, *> -> item
                    else -> null
                } ?: return@forEach
                val claim = issuerItemMap["elementIdentifier"] as? String ?: return@forEach
                val value = issuerItemMap["elementValue"]
                claims["$namespace.$claim"] = value
            }
        }
        return MdocCredentialDocument(
            docType = "unknown",
            namespace = claims.keys.firstOrNull()?.substringBeforeLast('.') ?: "unknown",
            claims = claims,
        )
    }

    private fun buildDeviceAuthenticationPayload(
        docType: String,
        audience: String,
        nonce: String,
        deviceNameSpacesBytes: DeviceNameSpacesBytes,
    ): ByteArray {
        val sessionTranscript = CBORPairList(
            CBORPair(CBORString("aud"), CBORString(audience)),
            CBORPair(CBORString("nonce"), CBORString(nonce)),
        )
        return CBORItemList(
            CBORString("DeviceAuthentication"),
            sessionTranscript,
            CBORString(docType),
            deviceNameSpacesBytes,
        ).encode()
    }

    private fun extractIssuerSignedItem(raw: String): CBORItem? {
        val root = decodeCborItem(raw) ?: return null
        val directNamespaces = readPairValue(root, "nameSpaces")
        val directIssuerAuth = readPairValue(root, "issuerAuth")
        if (directNamespaces != null && directIssuerAuth != null) {
            return root
        }
        val docsItem = readPairValue(root, "documents") as? CBORItemList ?: return null
        val firstDoc = docsItem.items.firstOrNull() as? CBORPairList ?: return null
        return readPairValue(firstDoc, "issuerSigned")
    }

    private fun decodeCborItem(rawB64: String): CBORItem? {
        val bytes = decodeBase64Url(rawB64) ?: return null
        return runCatching { CBORDecoder(bytes).next() }.getOrNull()
    }

    private fun decodeCbor(bytes: ByteArray): Any? {
        return runCatching {
            val item = CBORDecoder(bytes).next()
            item.parse()
        }.getOrNull()
    }

    private fun decodeBase64Url(value: String): ByteArray? {
        val clean = value.trim()
        if (clean.isBlank()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
    }

    private fun readPairValue(item: CBORItem, key: String): CBORItem? {
        val pairList = item as? CBORPairList ?: return null
        return pairList.pairs.firstOrNull { pair ->
            (pair.key as? CBORString)?.value == key
        }?.value
    }

    private fun selfSignedCertificate(keyPair: KeyPair, subject: String): X509Certificate {
        if (initProvider.compareAndSet(false, true)) {
            Security.addProvider(BouncyCastleProvider())
        }
        val now = Date()
        val notAfter = Date(now.time + 365L * 24L * 60L * 60L * 1000L)
        val subjectName = X500Name(subject)
        val builder = JcaX509v3CertificateBuilder(
            subjectName,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            notAfter,
            subjectName,
            keyPair.public,
        )
        val signer: ContentSigner = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider("BC")
            .build(keyPair.private)
        val holder: X509CertificateHolder = builder.build(signer)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
    }

    private fun ecKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private fun toCoseEc2Key(privateKey: ECPrivateKey, publicKey: ECPublicKey): COSEEC2Key {
        val x = toFixed(publicKey.w.affineX.toByteArray(), 32)
        val y = toFixed(publicKey.w.affineY.toByteArray(), 32)
        val d = toFixed(privateKey.s.toByteArray(), 32)
        return com.authlete.cose.COSEKeyBuilder()
            .ktyEC2()
            .ec2CrvP256()
            .ec2X(x)
            .ec2Y(y)
            .ec2D(d)
            .buildEC2Key()
    }

    private fun toCoseEc2PublicKey(publicKey: ECPublicKey): COSEEC2Key {
        val x = toFixed(publicKey.w.affineX.toByteArray(), 32)
        val y = toFixed(publicKey.w.affineY.toByteArray(), 32)
        return com.authlete.cose.COSEKeyBuilder()
            .ktyEC2()
            .ec2CrvP256()
            .ec2X(x)
            .ec2Y(y)
            .buildEC2Key()
    }

    private fun toFixed(raw: ByteArray, size: Int): ByteArray {
        if (raw.size == size) return raw
        if (raw.size > size) return raw.copyOfRange(raw.size - size, raw.size)
        return ByteArray(size - raw.size) + raw
    }
}
