package di.swallet.wpb.presentation.registry

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import di.swallet.wpb.presentation.domain.RegistryCredentialDescriptor
import di.swallet.wpb.presentation.domain.RegistryIntendedUse
import di.swallet.wpb.presentation.domain.RpRegistryRecord
import di.swallet.wpb.presentation.domain.SupervisoryAuthorityContact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface RegistryResolution {
    data class Accepted(
        val record: RpRegistryRecord,
        val sourceEndpoint: String,
        val intendedUseChecked: Boolean,
    ) : RegistryResolution

    data class Rejected(
        val reason: String,
        val sourceEndpoint: String? = null,
    ) : RegistryResolution
}

@Component
class RpRegistryResolver(
    private val properties: OpenId4VpProperties,
    private val client: RpRegistryClient,
    private val signatureVerifier: RpRegistrySignatureVerifier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CachedRegistryRecord>()

    fun resolveAndValidate(
        rpIdentifier: String,
        credentialQueries: List<CredentialQuery>,
    ): RegistryResolution {
        val recordResolution = resolveRecord(rpIdentifier)
        val record = when (recordResolution) {
            is RegistryResolution.Rejected -> return recordResolution
            is RegistryResolution.Accepted -> recordResolution.record
        }

        return validateIntendedUse(
            rpIdentifier = rpIdentifier,
            record = record,
            credentialQueries = credentialQueries,
            recordSourceEndpoint = (recordResolution as RegistryResolution.Accepted).sourceEndpoint,
        )
    }

    private fun resolveRecord(rpIdentifier: String): RegistryResolution {
        val cached = cache[rpIdentifier]
        if (cached != null && !isExpired(cached.cachedAt)) {
            logger.info("event=registry.lookup.cache_hit identifier={}", rpIdentifier)
            return RegistryResolution.Accepted(
                record = cached.record,
                sourceEndpoint = cached.sourceEndpoint,
                intendedUseChecked = false,
            )
        }
        logger.info("event=registry.lookup.cache_miss identifier={}", rpIdentifier)

        val primary = runLookup(client.getByIdentifier(rpIdentifier))
        if (primary is RegistryLookupOutcome.Found) {
            cache[rpIdentifier] = CachedRegistryRecord(primary.record, Instant.now(), primary.endpoint)
            return RegistryResolution.Accepted(primary.record, primary.endpoint, intendedUseChecked = false)
        }
        if (primary is RegistryLookupOutcome.Rejected) return RegistryResolution.Rejected(primary.reason, primary.endpoint)

        val fallback = runLookup(client.queryByIdentifier(rpIdentifier))
        if (fallback is RegistryLookupOutcome.Found) {
            cache[rpIdentifier] = CachedRegistryRecord(fallback.record, Instant.now(), fallback.endpoint)
            return RegistryResolution.Accepted(fallback.record, fallback.endpoint, intendedUseChecked = false)
        }
        return when (fallback) {
            is RegistryLookupOutcome.Rejected -> RegistryResolution.Rejected(fallback.reason, fallback.endpoint)
            RegistryLookupOutcome.NotFound -> RegistryResolution.Rejected("RP '$rpIdentifier' not found in TS5 registry")
            RegistryLookupOutcome.Unavailable -> RegistryResolution.Rejected("TS5 registry unavailable for '$rpIdentifier'")
            null -> RegistryResolution.Rejected("TS5 registry unavailable for '$rpIdentifier'")
            else -> RegistryResolution.Rejected("unexpected registry lookup state")
        }
    }

    private fun validateIntendedUse(
        rpIdentifier: String,
        record: RpRegistryRecord,
        credentialQueries: List<CredentialQuery>,
        recordSourceEndpoint: String,
    ): RegistryResolution {
        if (credentialQueries.isEmpty()) {
            return RegistryResolution.Accepted(record, recordSourceEndpoint, intendedUseChecked = false)
        }

        if (properties.registry.preferCheckIntendedUseEndpoint) {
            val checkResult = runCheckIntendedUse(rpIdentifier, record, credentialQueries)
            if (checkResult != null) return checkResult
        }

        val local = localIntendedUseCheck(record, credentialQueries)
        return if (local) {
            RegistryResolution.Accepted(record, "local-fallback", intendedUseChecked = true)
        } else {
            RegistryResolution.Rejected(
                reason = "Intended use mismatch against TS6 registered data",
                sourceEndpoint = "local-fallback",
            )
        }
    }

    private fun runCheckIntendedUse(
        rpIdentifier: String,
        record: RpRegistryRecord,
        credentialQueries: List<CredentialQuery>,
    ): RegistryResolution? {
        credentialQueries.forEach { query ->
            val claimPaths = query.requestedClaims.ifEmpty { listOf("") }
            claimPaths.forEach { claimPath ->
                val response = runCatching {
                    client.checkIntendedUse(
                        rpIdentifier = rpIdentifier,
                        intendedUseIdentifier = record.intendedUses.firstOrNull()?.intendedUseIdentifier,
                        credentialFormat = RegistryIntendedUseMatcher.toTs5Format(query.format),
                        claimPath = claimPath.takeIf { it.isNotBlank() },
                    )
                }.getOrElse { ex ->
                    logger.warn("event=registry.lookup.failed_transport endpoint=/wrp/check-intended-use reason={}", ex.message)
                    return null
                } ?: return null

                if (response.statusCode == 404) return null
                if (response.statusCode !in 200..299) {
                    return RegistryResolution.Rejected(
                        reason = "TS5 check-intended-use rejected with status ${response.statusCode}",
                        sourceEndpoint = response.endpoint,
                    )
                }
                val body = response.body?.trim().orEmpty()
                if (body.isBlank()) {
                    return RegistryResolution.Rejected("TS5 check-intended-use returned empty response", response.endpoint)
                }
                val verified = runCatching { signatureVerifier.verifyCompactJws(body, response.endpoint) }.getOrElse { ex ->
                    return RegistryResolution.Rejected("TS5 check-intended-use signature validation failed: ${ex.message}", response.endpoint)
                }
                val result = verified.dataElement.jsonObject["isRegistered"]?.jsonPrimitive?.booleanOrNull
                    ?: return RegistryResolution.Rejected("TS5 check-intended-use missing data.isRegistered", response.endpoint)
                if (!result) {
                    return RegistryResolution.Rejected(
                        "RP '$rpIdentifier' is not registered for requested intended use",
                        response.endpoint,
                    )
                }
            }
        }
        return RegistryResolution.Accepted(record, "/wrp/check-intended-use", intendedUseChecked = true)
    }

    private fun localIntendedUseCheck(record: RpRegistryRecord, credentialQueries: List<CredentialQuery>): Boolean =
        RegistryIntendedUseMatcher.coversQueries(record, credentialQueries)

    private fun runLookup(response: RegistryHttpResponse?): RegistryLookupOutcome? {
        response ?: return RegistryLookupOutcome.Unavailable
        if (response.statusCode == 404) return RegistryLookupOutcome.NotFound
        if (response.statusCode !in 200..299) {
            return RegistryLookupOutcome.Rejected(
                "TS5 registry lookup failed with status ${response.statusCode}",
                response.endpoint,
            )
        }
        val body = response.body?.trim().orEmpty()
        if (body.isBlank()) {
            return RegistryLookupOutcome.Rejected("TS5 registry returned empty body", response.endpoint)
        }
        val verified = runCatching { signatureVerifier.verifyCompactJws(body, response.endpoint) }.getOrElse { ex ->
            return RegistryLookupOutcome.Rejected(
                "TS5 registry signature validation failed: ${ex.message}",
                response.endpoint,
            )
        }

        val record = when (val data = verified.dataElement) {
            is JsonObject -> parseRecord(data, body, verified.payloadJson.toString())
            is JsonArray -> {
                if (data.isEmpty()) return RegistryLookupOutcome.NotFound
                if (data.size > 1) {
                    return RegistryLookupOutcome.Rejected(
                        "TS5 lookup returned multiple RP records (ambiguous identity)",
                        response.endpoint,
                    )
                }
                parseRecord(data.first().jsonObject, body, verified.payloadJson.toString())
            }
            else -> return RegistryLookupOutcome.Rejected(
                "TS5 registry 'data' claim has unsupported shape",
                response.endpoint,
            )
        }
        return RegistryLookupOutcome.Found(record, response.endpoint)
    }

    private fun parseRecord(data: JsonObject, signedJwt: String, payloadJson: String): RpRegistryRecord {
        val identifier = data["identifier"].extractIdentifiers().firstOrNull()
            ?: throw IllegalStateException("TS5 record missing WalletRelyingParty.identifier")
        val supportUris = data["supportURI"].extractStringValues()
        val entitlements = data["entitlements"].extractStringValues()
        val intendedUses = (data["intendedUse"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { intended ->
            RegistryIntendedUse(
                intendedUseIdentifier = intended["intendedUseIdentifier"]?.jsonPrimitive?.contentOrNull,
                purpose = intended["purpose"].extractMultiLangContents(),
                privacyPolicyUris = intended["privacyPolicy"].extractPolicyUris(),
                credentials = intended["credential"].extractCredentialDescriptors(),
            )
        }
        val supervisory = (data["supervisoryAuthority"] as? JsonObject)?.let { sa ->
            SupervisoryAuthorityContact(
                name = sa["name"]?.jsonPrimitive?.contentOrNull,
                country = sa["country"]?.jsonPrimitive?.contentOrNull,
                email = sa["email"].extractStringValues(),
                phone = sa["phone"].extractStringValues(),
                formUri = sa["formURI"].extractStringValues(),
            )
        }
        return RpRegistryRecord(
            identifier = identifier,
            tradeName = data["tradeName"]?.jsonPrimitive?.contentOrNull,
            registryUri = data["registryURI"]?.jsonPrimitive?.contentOrNull,
            supportUris = supportUris,
            entitlements = entitlements,
            supervisoryAuthority = supervisory,
            intendedUses = intendedUses,
            rawSignedJwt = signedJwt,
            rawJwtPayloadJson = payloadJson,
            rawDataJson = data.toString(),
        )
    }

    private fun JsonElement?.extractStringValues(): List<String> = when (this) {
        is JsonArray -> this.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(this.contentOrNull)
        else -> emptyList()
    }

    private fun JsonElement?.extractIdentifiers(): List<String> = when (this) {
        is JsonArray -> this.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj["identifier"]?.jsonPrimitive?.contentOrNull
        }
        is JsonPrimitive -> listOfNotNull(this.contentOrNull)
        else -> emptyList()
    }

    private fun JsonElement?.extractMultiLangContents(): List<String> = when (this) {
        is JsonArray -> this.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj["content"]?.jsonPrimitive?.contentOrNull
        }
        else -> emptyList()
    }

    private fun JsonElement?.extractPolicyUris(): List<String> = when (this) {
        is JsonArray -> this.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj["policyURI"]?.jsonPrimitive?.contentOrNull
        }
        else -> emptyList()
    }

    private fun JsonElement?.extractCredentialDescriptors(): List<RegistryCredentialDescriptor> = when (this) {
        is JsonArray -> this.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val claimPaths = (obj["claim"] as? JsonArray).orEmpty().mapNotNull { claimItem ->
                val claimObj = claimItem as? JsonObject ?: return@mapNotNull null
                claimObj["path"]?.jsonPrimitive?.contentOrNull
            }
            RegistryCredentialDescriptor(
                format = obj["format"]?.jsonPrimitive?.contentOrNull,
                meta = obj["meta"]?.jsonPrimitive?.contentOrNull,
                claimPaths = claimPaths,
            )
        }
        else -> emptyList()
    }

    private fun isExpired(cachedAt: Instant): Boolean {
        val ttl = Duration.ofSeconds(properties.registry.maxCacheAgeSeconds.coerceAtLeast(1))
        return cachedAt.plus(ttl).isBefore(Instant.now())
    }

    private data class CachedRegistryRecord(
        val record: RpRegistryRecord,
        val cachedAt: Instant,
        val sourceEndpoint: String,
    )

    private sealed interface RegistryLookupOutcome {
        data class Found(val record: RpRegistryRecord, val endpoint: String) : RegistryLookupOutcome
        data class Rejected(val reason: String, val endpoint: String? = null) : RegistryLookupOutcome
        data object NotFound : RegistryLookupOutcome
        data object Unavailable : RegistryLookupOutcome
    }
}
