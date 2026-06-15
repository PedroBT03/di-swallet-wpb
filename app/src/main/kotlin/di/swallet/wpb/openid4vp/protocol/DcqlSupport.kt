/**
 * Flexible DCQL parser for verifier credential queries.
 */

package di.swallet.wpb.openid4vp.protocol

import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.presentation.domain.ClaimPathSegment
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best-effort DCQL parser used by the SDK adapter fallback path and credential matcher.
 * Accepts both standard `credentials[]` and emulator `query[]` shapes.
 */
object DcqlSupport {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Parses DCQL JSON into credential queries, returning an empty list on failure. */
    fun parse(dcqlJson: String?): List<CredentialQuery> {
        if (dcqlJson.isNullOrBlank()) return emptyList()
        return try {
            val root = lenientJson.parseToJsonElement(dcqlJson)
            val entries = extractEntries(root)
            entries.mapIndexedNotNull { index, entry ->
                toCredentialQuery(index, entry)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Reads credential query entries from supported DCQL root shapes. */
    private fun extractEntries(root: JsonElement): List<JsonElement> {
        if (root !is JsonObject) return emptyList()
        root["credentials"]?.let { entries ->
            if (entries is JsonArray) return entries.toList()
        }
        root["query"]?.let { entries ->
            if (entries is JsonArray) return entries.toList()
        }
        return emptyList()
    }

    /** Converts one DCQL credential entry into a [CredentialQuery]. */
    private fun toCredentialQuery(index: Int, entry: JsonElement): CredentialQuery? {
        if (entry !is JsonObject) return null
        val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: "query_$index"
        val format = entry["format"]?.jsonPrimitive?.contentOrNull?.let(::toFormat) ?: CredentialFormat.SD_JWT
        val typeHints = extractTypeHints(entry)
        val claimPaths = extractClaimPaths(entry)
        return CredentialQuery(
            id = id,
            format = format,
            credentialTypeHints = typeHints,
            requestedClaimPaths = claimPaths,
        )
    }

    /** Maps DCQL format strings to wallet [CredentialFormat] values. */
    private fun toFormat(raw: String): CredentialFormat = when (raw.lowercase()) {
        "vc+sd-jwt", "dc+sd-jwt", "sd-jwt", "sd_jwt" -> CredentialFormat.SD_JWT
        "mso_mdoc", "mdoc", "mso-mdoc" -> CredentialFormat.MDOC
        else -> CredentialFormat.SD_JWT
    }

    /** Extracts vct and doctype hints from DCQL meta fields. */
    private fun extractTypeHints(entry: JsonObject): List<String> {
        val meta = entry["meta"] as? JsonObject ?: return emptyList()
        val vctValues = (meta["vct_values"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val docTypeValues = (meta["doctype_values"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val docTypeSingle = listOfNotNull(meta["doctype"]?.jsonPrimitive?.contentOrNull)
        return (vctValues + docTypeValues + docTypeSingle).distinct()
    }

    /** Extracts requested claim paths from `claims` or legacy `fields` arrays. */
    private fun extractClaimPaths(entry: JsonObject): List<ClaimPath> {
        val claimsArray = entry["claims"] as? JsonArray
        if (claimsArray != null) {
            return claimsArray.mapNotNull { claim ->
                val obj = claim as? JsonObject ?: return@mapNotNull null
                parseClaimPathElement(obj["path"])
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull?.let { ClaimPath.key(it) }
            }
        }
        val fieldsArray = entry["fields"] as? JsonArray
        if (fieldsArray != null) {
            return fieldsArray.mapNotNull { field ->
                field.jsonPrimitive.contentOrNull?.let { ClaimPath.key(it) }
            }
        }
        return emptyList()
    }

    /** Parses one DCQL claim path element into a [ClaimPath]. */
    private fun parseClaimPathElement(pathElement: JsonElement?): ClaimPath? {
        val segments = when (pathElement) {
            is JsonArray -> pathElement.mapNotNull { segment -> parsePathSegment(segment) }
            is JsonPrimitive -> pathElement.contentOrNull?.let { listOf(ClaimPathSegment.Key(it)) }
            null, JsonNull -> null
            else -> null
        }
        return ClaimPath.fromDcqlPath(segments ?: return null)
    }

    /** Parses one DCQL path segment into a key, index, or wildcard segment. */
    private fun parsePathSegment(segment: JsonElement): ClaimPathSegment? = when (segment) {
        JsonNull -> ClaimPathSegment.Wildcard
        is JsonPrimitive -> when {
            segment.isString -> ClaimPathSegment.Key(segment.content)
            else -> segment.content.toIntOrNull()?.let { ClaimPathSegment.Index(it) }
                ?: segment.content.toLongOrNull()?.let { ClaimPathSegment.Index(it.toInt()) }
        }
        else -> null
    }
}
