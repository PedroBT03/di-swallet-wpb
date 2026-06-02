package di.swallet.wpb.openid4vp.protocol

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
 * Best-effort DCQL parser used both by the SDK adapter fallback path and by the
 * credential matcher.
 *
 * The parser is intentionally flexible: it accepts both the standard DCQL
 * shape used by the EUDI OpenID4VP SDK (`{ "credentials": [...] }`) and the
 * simplified shape produced by the local verifier emulator (`{ "query": [...] }`).
 */
object DcqlSupport {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

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

    private fun extractEntries(root: JsonElement): List<JsonElement> {
        if (root !is JsonObject) return emptyList()
        // Standard DCQL: credentials[]
        root["credentials"]?.let { entries ->
            if (entries is JsonArray) return entries.toList()
        }
        // Local emulator shape: query[]
        root["query"]?.let { entries ->
            if (entries is JsonArray) return entries.toList()
        }
        return emptyList()
    }

    private fun toCredentialQuery(index: Int, entry: JsonElement): CredentialQuery? {
        if (entry !is JsonObject) return null
        val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: "query_$index"
        val format = entry["format"]?.jsonPrimitive?.contentOrNull?.let(::toFormat) ?: CredentialFormat.SD_JWT
        val typeHints = extractTypeHints(entry)
        val claims = extractClaims(entry)
        return CredentialQuery(
            id = id,
            format = format,
            credentialTypeHints = typeHints,
            requestedClaims = claims,
        )
    }

    private fun toFormat(raw: String): CredentialFormat = when (raw.lowercase()) {
        "vc+sd-jwt", "dc+sd-jwt", "sd-jwt", "sd_jwt" -> CredentialFormat.SD_JWT
        "mso_mdoc", "mdoc", "mso-mdoc" -> CredentialFormat.MDOC
        else -> CredentialFormat.SD_JWT
    }

    private fun extractTypeHints(entry: JsonObject): List<String> {
        val meta = entry["meta"] as? JsonObject ?: return emptyList()
        val vctValues = (meta["vct_values"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val docTypeValues = (meta["doctype_values"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val docTypeSingle = listOfNotNull(meta["doctype"]?.jsonPrimitive?.contentOrNull)
        return (vctValues + docTypeValues + docTypeSingle).distinct()
    }

    private fun extractClaims(entry: JsonObject): List<String> {
        // Standard DCQL: claims[].path[]
        val claimsArray = entry["claims"] as? JsonArray
        if (claimsArray != null) {
            return claimsArray.mapNotNull { claim ->
                val obj = claim as? JsonObject ?: return@mapNotNull null
                when (val path = obj["path"]) {
                    is JsonArray -> path.firstOrNull()?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> path.contentOrNull
                    null, JsonNull -> obj["name"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.distinct()
        }
        // Emulator shape: fields[] is a flat array of names
        val fieldsArray = entry["fields"] as? JsonArray
        if (fieldsArray != null) {
            return fieldsArray.mapNotNull { it.jsonPrimitive.contentOrNull }.distinct()
        }
        return emptyList()
    }
}
