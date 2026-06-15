/**
 * Selects localized Trust Mark text from available language maps.
 */

package di.swallet.wpb.trustmark

import org.springframework.stereotype.Component

/** Picks the best matching localized Trust Mark string for a requested language. */
@Component
class TrustMarkLocalizationService {
    /** Returns the resolved language key and localized text for the requested or default language. */
    fun select(localizations: Map<String, String>, requestedLanguage: String?, defaultLanguage: String): Pair<String, String> {
        if (localizations.isEmpty()) return defaultLanguage to ""
        val normalizedDefault = defaultLanguage.lowercase()
        val requested = requestedLanguage?.lowercase()?.takeIf { it.isNotBlank() }
        if (requested != null) {
            localizations[requested]?.let { return requested to it }
            val prefixMatch = localizations.entries.firstOrNull { (key, _) ->
                key.lowercase().startsWith(requested)
            }
            if (prefixMatch != null) return prefixMatch.key to prefixMatch.value
        }
        localizations[normalizedDefault]?.let { return normalizedDefault to it }
        val first = localizations.entries.first()
        return first.key to first.value
    }
}
