/**
 * Parses LoTE and TS 119602 trust list payloads into neutral trust-core models.
 */

package di.swallet.wpb.trust.lote

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import di.swallet.wpb.trust.core.TrustBindingRule
import di.swallet.wpb.trust.core.TrustSnapshot
import di.swallet.wpb.trust.core.TrustedEntity
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

/** Translates LoTE-like payloads into neutral trust-core models without applying trust policy. */
@Component
class LoteTrustParser {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val certFactory = CertificateFactory.getInstance("X.509")

    /**
     * Accepted ETSI service statuses (case-insensitive):
     * - granted
     *
     * Ignored ETSI statuses (case-insensitive examples):
     * - withdrawn
     * - revoked
     * - suspended
     * - deprecated
     * - historical/history
     * - inactive
     */
    private val activeStatusMarkers = setOf("granted")
    private val inactiveStatusMarkers = setOf("withdrawn", "revoked", "suspended", "deprecated", "historical", "history", "inactive")

    /** Parses a LoTE JSON document in legacy or TS 119602 TrustedEntitiesList format. */
    fun parseDocument(payload: String): LoteTrustDocument {
        val root = mapper.readTree(payload)
        if (root["TrustedEntitiesList"]?.isArray == true) {
            return parseTs119602Document(root)
        }
        val entitiesNode = when {
            root["entities"]?.isArray == true -> root["entities"]
            root["verifiers"]?.isArray == true -> root["verifiers"] // backward-compatible legacy source
            else -> null
        }
        val entities = entitiesNode?.mapNotNull(::parseEntity).orEmpty()
        val anchors = root["trustAnchorsPem"]?.takeIf { it.isArray }
            ?.mapNotNull { it.asText()?.trim()?.ifBlank { null } }
            .orEmpty()
        val validUntil = root["validUntil"]?.asText()?.trim()?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
        return LoteTrustDocument(
            entities = entities,
            trustAnchorsPem = anchors,
            sequenceNumber = root["sequenceNumber"]?.asText(),
            issueDate = parseInstant(root["issueDate"]?.asText()),
            validUntil = validUntil,
            listType = root["listType"]?.asText(),
            schemeType = root["schemeType"]?.asText(),
        )
    }

    /** Converts a parsed LoTE document into a protocol-agnostic trust snapshot. */
    fun toTrustSnapshot(
        document: LoteTrustDocument,
        source: LoteTrustSource,
        loadedAt: Instant,
        trustAnchors: List<X509Certificate>,
    ): TrustSnapshot {
        val entities = document.entities.mapNotNull { entity ->
            val normalizedClientIds = entity.clientIds.mapNotNull { it.trim().ifBlank { null } }
            val canonicalEntityId = entity.entityId?.trim()?.takeIf { it.isNotBlank() }
                ?: normalizedClientIds.firstOrNull()?.let { "client_id:$it" }
                ?: return@mapNotNull null

            val bindings = buildSet {
                normalizedClientIds.forEach { add(TrustBindingRule("client_id", it)) }
                entity.sanDns.mapNotNull { it.trim().ifBlank { null } }.forEach { add(TrustBindingRule("san_dns", it)) }
                entity.sanUri.mapNotNull { it.trim().ifBlank { null } }.forEach { add(TrustBindingRule("san_uri", it)) }
                entity.subjectCn.mapNotNull { it.trim().ifBlank { null } }.forEach { add(TrustBindingRule("subject_cn", it)) }
                entity.certSha256.mapNotNull { it.trim().uppercase().ifBlank { null } }.forEach { add(TrustBindingRule("cert_sha256", it)) }
            }

            canonicalEntityId to TrustedEntity(
                entityId = canonicalEntityId,
                bindings = bindings,
                metadata = entity.metadata + mapOf(
                    "etsi.sequenceNumber" to (document.sequenceNumber ?: ""),
                    "etsi.issueDate" to (document.issueDate?.toString() ?: ""),
                    "etsi.nextUpdate" to (document.validUntil?.toString() ?: ""),
                    "etsi.listType" to (document.listType ?: ""),
                    "etsi.schemeType" to (document.schemeType ?: ""),
                ).filterValues { it.isNotBlank() },
            )
        }.toMap()

        return TrustSnapshot(
            trustAnchors = trustAnchors,
            entities = entities,
            source = source.label,
            loadedAt = loadedAt,
            validUntil = document.validUntil,
        )
    }

    /** Parses a TS 119602 TrustedEntitiesList document root node. */
    private fun parseTs119602Document(root: JsonNode): LoteTrustDocument {
        val listInfo = root["ListAndSchemeInformation"]
        val sequenceNumber = listInfo?.get("LoTESequenceNumber")?.asText()
            ?: listInfo?.get("TSLSequenceNumber")?.asText()
        val issueDate = parseInstant(listInfo?.get("ListIssueDateTime")?.asText())
            ?: parseInstant(listInfo?.get("IssueDateTime")?.asText())
        val validUntil = parseInstant(
            listInfo?.get("NextUpdate")?.get("dateTime")?.asText()
                ?: listInfo?.get("NextUpdate")?.asText(),
        )
        val listType = listInfo?.get("LoTEType")?.asText() ?: listInfo?.get("TSLType")?.asText()
        val schemeType = listInfo?.get("StatusDeterminationApproach")?.asText()
            ?: listInfo?.get("SchemeTypeCommunityRules")?.firstOrNull()?.asText()

        val entities = root["TrustedEntitiesList"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { parseTsEntity(it, sequenceNumber, issueDate, validUntil, listType, schemeType) }
            .orEmpty()

        val anchors = root["trustAnchorsPem"]?.takeIf { it.isArray }
            ?.mapNotNull { it.asText()?.trim()?.ifBlank { null } }
            .orEmpty()

        return LoteTrustDocument(
            entities = entities,
            trustAnchorsPem = anchors,
            sequenceNumber = sequenceNumber,
            issueDate = issueDate,
            validUntil = validUntil,
            listType = listType,
            schemeType = schemeType,
        )
    }

    /** Parses one TS 119602 trusted entity with active services and certificate bindings. */
    private fun parseTsEntity(
        node: JsonNode,
        sequenceNumber: String?,
        issueDate: Instant?,
        validUntil: Instant?,
        listType: String?,
        schemeType: String?,
    ): LoteTrustEntityDocument? {
        val services = node["TrustedEntityServices"]?.takeIf { it.isArray }?.toList().orEmpty()
        val activeServices = services.mapNotNull { parseActiveService(it) }
        if (activeServices.isEmpty()) {
            // Ignore entities with no active services.
            return null
        }

        val trustedEntityInfo = node["TrustedEntityInformation"]
        val explicitEntityId = trustedEntityInfo?.get("TEIdentifier")?.asText()?.trim()?.takeIf { it.isNotBlank() }
            ?: node["entityId"]?.asText()?.trim()?.takeIf { it.isNotBlank() }
        val clientIds = readTextArray(node["clientIds"]) +
            listOfNotNull(node["clientId"]?.asText()?.trim()?.takeIf { it.isNotBlank() })

        val certs = activeServices.flatMap { it.certificates }
        val certSha256 = certs.map { fingerprint(it) }.toSet().toList()
        val sanDns = certs.flatMap { extractSan(it, 2) }.toSet().toList()
        val sanUri = certs.flatMap { extractSan(it, 6) }.toSet().toList()
        val subjectCn = certs.mapNotNull(::extractSubjectCn).toSet().toList()

        val serviceMetadataList = activeServices.map { svc ->
            mapOf(
                "serviceType" to svc.serviceType,
                "serviceStatus" to svc.serviceStatus,
                "serviceIdentifiers" to svc.identifiers.joinToString(","),
            )
        }
        val metadata = mutableMapOf<String, String>()
        metadata["etsi.sequenceNumber"] = sequenceNumber.orEmpty()
        metadata["etsi.issueDate"] = issueDate?.toString().orEmpty()
        metadata["etsi.nextUpdate"] = validUntil?.toString().orEmpty()
        metadata["etsi.listType"] = listType.orEmpty()
        metadata["etsi.schemeType"] = schemeType.orEmpty()
        metadata["etsi.serviceType"] = activeServices.mapNotNull { it.serviceType }.distinct().joinToString(",")
        metadata["etsi.serviceStatus"] = activeServices.mapNotNull { it.serviceStatus }.distinct().joinToString(",")
        metadata["etsi.serviceIdentifiers"] = activeServices.flatMap { it.identifiers }.distinct().joinToString(",")
        metadata["etsi.services.json"] = mapper.writeValueAsString(serviceMetadataList)

        if (explicitEntityId == null && clientIds.isEmpty()) return null
        return LoteTrustEntityDocument(
            entityId = explicitEntityId,
            clientIds = clientIds.distinct(),
            sanDns = sanDns,
            sanUri = sanUri,
            subjectCn = subjectCn,
            certSha256 = certSha256,
            metadata = metadata.filterValues { it.isNotBlank() },
        )
    }

    /** Parses one legacy or simplified LoTE entity node. */
    private fun parseEntity(node: JsonNode): LoteTrustEntityDocument? {
        val explicitEntityId = node["entityId"]?.asText()?.trim()?.takeIf { it.isNotBlank() }
        val legacyClientId = node["clientId"]?.asText()?.trim()?.takeIf { it.isNotBlank() }
        val clientIds = when {
            node["clientIds"]?.isArray == true -> node["clientIds"].mapNotNull { it.asText()?.trim()?.ifBlank { null } }
            legacyClientId != null -> listOf(legacyClientId)
            else -> emptyList()
        }
        if (explicitEntityId == null && clientIds.isEmpty()) return null

        val metadata = node["metadata"]?.takeIf { it.isObject }
            ?.fields()
            ?.asSequence()
            ?.associate { it.key to it.value.asText() }
            .orEmpty()

        return LoteTrustEntityDocument(
            entityId = explicitEntityId,
            clientIds = clientIds,
            sanDns = readTextArray(node["sanDns"]),
            sanUri = readTextArray(node["sanUri"]),
            subjectCn = readTextArray(node["subjectCn"]),
            certSha256 = readTextArray(node["certSha256"]),
            metadata = metadata,
        )
    }

    /** Reads string values from a JSON array node, skipping blanks. */
    private fun readTextArray(node: JsonNode?): List<String> {
        if (node == null || !node.isArray) return emptyList()
        return node.mapNotNull { it.asText()?.trim()?.ifBlank { null } }
    }

    /** Parsed ETSI service entry with certificates extracted from digital identity. */
    private data class ActiveService(
        val serviceType: String?,
        val serviceStatus: String?,
        val identifiers: List<String>,
        val certificates: List<X509Certificate>,
    )

    /** Parses one service node and keeps it only when the ETSI status is active. */
    private fun parseActiveService(serviceNode: JsonNode): ActiveService? {
        val info = serviceNode["ServiceInformation"] ?: serviceNode
        val status = info["ServiceStatus"]?.asText()?.trim()
        if (!isStatusActive(status)) return null

        val type = info["ServiceTypeIdentifier"]?.asText()?.trim()
        val identifiers = mutableListOf<String>()
        identifiers += readUriValues(info["ServiceInformationURI"])
        identifiers += readUriValues(info["ServiceSupplyPoints"])
        identifiers += readTextArray(info["ServiceName"])
        identifiers += listOfNotNull(info["ServiceIdentifier"]?.asText()?.trim()?.takeIf { it.isNotBlank() })

        val certificates = extractServiceCertificates(info["ServiceDigitalIdentity"])
        return ActiveService(
            serviceType = type,
            serviceStatus = status,
            identifiers = identifiers.distinct(),
            certificates = certificates,
        )
    }

    /** Returns true when the ETSI service status is granted and not explicitly inactive. */
    private fun isStatusActive(status: String?): Boolean {
        val raw = status?.trim()?.lowercase() ?: return false
        if (inactiveStatusMarkers.any { raw.contains(it) }) return false
        return activeStatusMarkers.any { raw == it || raw.contains("/$it") || raw.endsWith(":$it") }
    }

    /** Extracts X.509 certificates from a ServiceDigitalIdentity node. */
    private fun extractServiceCertificates(digitalIdentity: JsonNode?): List<X509Certificate> {
        if (digitalIdentity == null || digitalIdentity.isNull) return emptyList()
        val certNodes = digitalIdentity["X509Certificates"]?.takeIf { it.isArray } ?: return emptyList()
        return certNodes.mapNotNull { node ->
            val b64 = when {
                node.isTextual -> node.asText()
                node.isObject -> node["val"]?.asText()
                else -> null
            }?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            runCatching {
                val der = Base64.getDecoder().decode(b64)
                certFactory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            }.getOrNull()
        }
    }

    /** Reads URI values from textual nodes or uriValue object entries. */
    private fun readUriValues(node: JsonNode?): List<String> {
        if (node == null) return emptyList()
        if (!node.isArray) return emptyList()
        return node.mapNotNull { item ->
            when {
                item.isTextual -> item.asText().trim().ifBlank { null }
                item.isObject -> item["uriValue"]?.asText()?.trim()?.ifBlank { null }
                else -> null
            }
        }
    }

    /** Parses an ISO-8601 instant string, returning null on invalid input. */
    private fun parseInstant(raw: String?): Instant? {
        val normalized = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(normalized) }.getOrNull()
    }

    /** Returns the uppercase SHA-256 fingerprint of a certificate DER encoding. */
    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }

    /** Extracts subject alternative names of the given SAN type from a certificate. */
    private fun extractSan(cert: X509Certificate, type: Int): List<String> =
        cert.subjectAlternativeNames
            ?.mapNotNull { san -> san.getOrNull(0) to san.getOrNull(1) }
            ?.filter { (sanType, _) -> sanType == type }
            ?.mapNotNull { (_, value) -> value as? String }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Extracts the common name from an X.509 subject distinguished name. */
    private fun extractSubjectCn(cert: X509Certificate): String? {
        val dn = cert.subjectX500Principal.name
        return dn.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
