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
import com.authlete.cose.COSEUnprotectedHeader
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
import di.swallet.wpb.config.MdocProperties
import di.swallet.wpb.domain.WalletKeyRepository
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64

@Component
class MdocIsoRuntimeService(
    private val properties: MdocProperties,
    private val issuerKeyStore: MdocIssuerKeyStore,
    private val credentialVerifier: MdocCredentialVerifier,
    private val deviceAuthSigner: MdocDeviceAuthSigner,
    private val sessionTranscriptBuilder: MdocSessionTranscriptBuilder,
    private val walletKeyRepository: WalletKeyRepository,
) {
    fun issueIssuerSigned(
        docType: String,
        namespaceClaims: Map<String, Map<String, Any?>>,
        devicePublicCoseKey: COSEEC2Key,
    ): String {
        val issuer = issuerKeyStore.material()
        val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
        val validityInfo = ValidityInfo(now, now, now.plusYears(1))
        val claims = namespaceClaims.mapValues { it.value as Any }.toMap()
        val issuerSigned: IssuerSigned = IssuerSignedBuilder()
            .setDocType(docType)
            .setClaims(claims)
            .setValidityInfo(validityInfo)
            .setDeviceKey(devicePublicCoseKey)
            .setIssuerKey(issuer.coseKey)
            .setIssuerCertChain(listOf(issuer.certificate))
            .build()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(issuerSigned.encode())
    }

    fun buildDeviceResponse(
        originalIssuedPayload: String?,
        docType: String,
        namespaceClaims: Map<String, Map<String, Any?>>,
        requestedClaims: List<String>,
        handover: MdocOpenId4VpHandover,
        holderKeyAlias: String? = null,
    ): String {
        val alias = holderKeyAlias?.trim().orEmpty()
        if (properties.requireHolderKeyAlias && alias.isBlank()) {
            throw IllegalStateException("mdoc presentation requires a holder HSM key alias")
        }
        if (alias.isNotBlank()) {
            assertDeviceKeyBinding(originalIssuedPayload, alias)
        }

        val issuerSignedItem = when {
            !originalIssuedPayload.isNullOrBlank() ->
                extractIssuerSignedItem(originalIssuedPayload)
                    ?: throw IllegalArgumentException("Invalid previously issued mdoc artifact for DeviceResponse build.")
            else -> {
                val fresh = issueIssuerSigned(
                    docType = docType,
                    namespaceClaims = namespaceClaims,
                    devicePublicCoseKey = requireDeviceKeyForAlias(alias),
                )
                decodeCborItem(fresh)
                    ?: throw IllegalStateException("Unable to decode freshly issued IssuerSigned artifact.")
            }
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
        val sessionTranscript = sessionTranscriptBuilder.buildSessionTranscript(handover)
        val deviceAuthenticationBytes = buildDeviceAuthenticationPayload(
            docType = docType,
            sessionTranscript = sessionTranscript,
            deviceNameSpacesBytes = deviceNameSpacesBytes,
        )
        val protectedHeader = COSEProtectedHeader.build(mapOf(1 to COSEAlgorithms.ES256))
        val sigStructure = SigStructure(
            protectedHeader,
            CBORByteArray(ByteArray(0)),
            CBORByteArray(deviceAuthenticationBytes),
        )
        val signature = if (alias.isNotBlank()) {
            deviceAuthSigner.signEs256(alias, sigStructure.encode())
        } else {
            throw IllegalStateException("mdoc presentation requires holder HSM key alias for device authentication")
        }
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

    fun validateIssuerSigned(raw: String): Boolean = credentialVerifier.validateIssuerSigned(raw)

    fun validateDeviceResponse(raw: String): Boolean = credentialVerifier.validateDeviceResponse(raw)

    private fun assertDeviceKeyBinding(originalIssuedPayload: String?, holderKeyAlias: String) {
        val payload = originalIssuedPayload?.trim().orEmpty()
        if (payload.isBlank()) return
        val expected = credentialVerifier.extractDeviceCosePublicKey(payload)
            ?: throw IllegalStateException("mdoc artifact does not contain a device public key")
        val walletKey = walletKeyRepository.findByKeyAlias(holderKeyAlias).orElse(null)
            ?: throw IllegalStateException("holder key alias '$holderKeyAlias' not found")
        val holderCose = MdocCoseKeyMaterial.toCoseEc2PublicKey(
            MdocCoseKeyMaterial.decodeEcPublicKey(walletKey.publicKeyBase64),
        )
        if (!cosePublicKeysMatch(expected, holderCose)) {
            throw IllegalStateException("holder key alias '$holderKeyAlias' does not match MSO device key binding")
        }
    }

    private fun requireDeviceKeyForAlias(alias: String): COSEEC2Key {
        if (alias.isBlank()) {
            throw IllegalStateException("mdoc issuance requires holder device public key")
        }
        val walletKey = walletKeyRepository.findByKeyAlias(alias).orElse(null)
            ?: throw IllegalStateException("holder key alias '$alias' not found for mdoc issuance")
        return MdocCoseKeyMaterial.toCoseEc2PublicKey(
            MdocCoseKeyMaterial.decodeEcPublicKey(walletKey.publicKeyBase64),
        )
    }

    private fun cosePublicKeysMatch(left: COSEEC2Key, right: COSEEC2Key): Boolean =
        left.encode().contentEquals(right.encode())

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
        sessionTranscript: CBORItem,
        deviceNameSpacesBytes: DeviceNameSpacesBytes,
    ): ByteArray = CBORItemList(
        CBORString("DeviceAuthentication"),
        sessionTranscript,
        CBORString(docType),
        deviceNameSpacesBytes,
    ).encode()

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

    private fun decodeCbor(bytes: ByteArray): Any? =
        runCatching {
            val item = CBORDecoder(bytes).next()
            item.parse()
        }.getOrNull()

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
}
