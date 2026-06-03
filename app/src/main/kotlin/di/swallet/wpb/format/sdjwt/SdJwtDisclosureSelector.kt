package di.swallet.wpb.format.sdjwt

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.presentation.domain.ClaimPathSegment
import org.springframework.stereotype.Component

/**
 * Resolves which SD-JWT disclosures must be included for DCQL claim paths, including
 * ancestor closure for nested `_sd` containers (SD-JWT VC §4.2).
 */
@Component
class SdJwtDisclosureSelector(
    private val objectMapper: ObjectMapper,
    private val sdJwtService: SdJwtService,
) {

    data class ParsedDisclosure(
        val raw: String,
        val claimName: String,
        val value: Any?,
        val digest: String,
    )

    fun parseDisclosures(stored: List<String>): List<ParsedDisclosure> =
        stored.mapNotNull { raw ->
            val claimName = readClaimName(raw) ?: return@mapNotNull null
            ParsedDisclosure(
                raw = raw,
                claimName = claimName,
                value = readClaimValue(raw),
                digest = sdJwtService.hashDisclosure(raw),
            )
        }

    /**
     * Returns disclosures matching requested paths (union), expanded with ancestor `_sd` closure.
     */
    fun selectDisclosures(stored: List<String>, paths: List<ClaimPath>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val parsed = parseDisclosures(stored)
        if (parsed.isEmpty()) return emptyList()

        val selected = linkedSetOf<ParsedDisclosure>()
        paths.forEach { path ->
            parsed.filter { matchesPath(it, path) }.forEach { selected.add(it) }
        }
        expandAncestorClosure(parsed, selected)
        return selected.map { it.raw }
    }

    fun canSatisfy(stored: List<String>, paths: List<ClaimPath>): Boolean {
        if (paths.isEmpty()) return true
        val parsed = parseDisclosures(stored)
        if (parsed.isEmpty()) return false
        return paths.all { path ->
            parsed.any { disclosure ->
                matchesPath(disclosure, path) && hasAncestorClosure(parsed, disclosure, path)
            }
        }
    }

    private fun matchesPath(disclosure: ParsedDisclosure, path: ClaimPath): Boolean {
        if (disclosure.claimName == path.toDotNotation()) return true
        val firstKey = path.segments.first() as? ClaimPathSegment.Key
        if (firstKey != null && disclosure.claimName == firstKey.name) {
            return valueMatchesPathSuffix(disclosure, path)
        }
        return matchesNestedLeafInObject(disclosure, path)
    }

    /**
     * Leaf disclosure inside a nested object (claim name = last key segment only).
     */
    private fun matchesNestedLeafInObject(disclosure: ParsedDisclosure, path: ClaimPath): Boolean {
        if (path.segments.size < 2) return false
        if (path.segments.any { it !is ClaimPathSegment.Key }) return false
        val leaf = path.leafKey()
        if (disclosure.claimName != leaf) return false
        return disclosure.value != null
    }

    private fun valueMatchesPathSuffix(disclosure: ParsedDisclosure, path: ClaimPath): Boolean {
        if (path.segments.size <= 1) return true
        val value = disclosure.value ?: return path.segments.drop(1).all { it is ClaimPathSegment.Key }
        return when (val tail = path.segments.drop(1)) {
            emptyList<ClaimPathSegment>() -> true
            else -> valueSatisfiesTail(value, tail)
        }
    }

    private fun valueSatisfiesTail(value: Any, tail: List<ClaimPathSegment>): Boolean {
        var current: Any? = value
        for (segment in tail) {
            current = when (segment) {
                is ClaimPathSegment.Key -> navigateKey(current, segment.name)
                is ClaimPathSegment.Index -> navigateIndex(current, segment.position)
                ClaimPathSegment.Wildcard -> navigateWildcard(current)
            } ?: return false
        }
        return true
    }

    private fun navigateKey(current: Any?, key: String): Any? {
        val map = asMap(current) ?: return null
        return map[key]
    }

    private fun navigateIndex(current: Any?, index: Int): Any? {
        val list = asList(current) ?: return null
        return list.getOrNull(index)
    }

    private fun navigateWildcard(current: Any?): Any? {
        val list = asList(current) ?: return null
        return if (list.isNotEmpty()) list.first() else null
    }

    private fun asMap(value: Any?): Map<*, *>? = when (value) {
        is Map<*, *> -> value
        null -> null
        else -> runCatching { objectMapper.convertValue(value, Map::class.java) }.getOrNull()
    }

    private fun asList(value: Any?): List<*>? = when (value) {
        is List<*> -> value
        is Array<*> -> value.toList()
        null -> null
        else -> runCatching { objectMapper.convertValue(value, List::class.java) }.getOrNull()
    }

    private fun hasAncestorClosure(
        all: List<ParsedDisclosure>,
        leaf: ParsedDisclosure,
        path: ClaimPath,
    ): Boolean {
        if (path.segments.size < 2) return true
        if (leaf.claimName == path.toDotNotation()) return true
        if (path.segments.any { it !is ClaimPathSegment.Key }) return true
        val parentKey = (path.segments.first() as ClaimPathSegment.Key).name
        if (leaf.claimName == path.leafKey() && leaf.claimName != parentKey) {
            val closure = expandAncestorClosure(all, linkedSetOf(leaf))
            return closure.any { it.claimName == parentKey }
        }
        return true
    }

    private fun expandAncestorClosure(
        all: List<ParsedDisclosure>,
        seed: MutableSet<ParsedDisclosure>,
    ): Set<ParsedDisclosure> {
        val parentsByChildDigest = buildParentIndex(all)
        var queue = seed.toMutableList()
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            parentsByChildDigest[current.digest].orEmpty().forEach { parent ->
                if (seed.add(parent)) {
                    queue.add(parent)
                }
            }
        }
        return seed
    }

    private fun buildParentIndex(all: List<ParsedDisclosure>): Map<String, List<ParsedDisclosure>> {
        val index = mutableMapOf<String, MutableList<ParsedDisclosure>>()
        all.forEach { parent ->
            sdDigestsInValue(parent.value).forEach { digest ->
                index.getOrPut(digest) { mutableListOf() }.add(parent)
            }
        }
        return index
    }

    private fun sdDigestsInValue(value: Any?): Set<String> {
        val map = asMap(value) ?: return emptySet()
        val sd = map["_sd"] ?: return emptySet()
        val list = asList(sd) ?: return emptySet()
        return list.mapNotNull { it as? String }.toSet()
    }

    private fun readClaimName(base64UrlDisclosure: String): String? {
        return try {
            val decoded = String(Base64URL(base64UrlDisclosure).decode())
            val asList = objectMapper.readValue(decoded, List::class.java)
            asList.getOrNull(1) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun readClaimValue(base64UrlDisclosure: String): Any? {
        return try {
            val decoded = String(Base64URL(base64UrlDisclosure).decode())
            val asList = objectMapper.readValue(decoded, List::class.java)
            asList.getOrNull(2)
        } catch (_: Throwable) {
            null
        }
    }
}
