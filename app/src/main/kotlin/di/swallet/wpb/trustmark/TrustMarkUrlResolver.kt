/**
 * Resolves relative Trust Mark resource URLs against configured base URLs.
 */

package di.swallet.wpb.trustmark

import org.springframework.stereotype.Component
import java.net.URI

/** Resolves Trust Mark image and link URLs relative to a configured HTTPS base. */
@Component
class TrustMarkUrlResolver {
    /** Returns an absolute URL, resolving relative values against the given base URL. */
    fun resolveAgainstBase(baseUrl: String, value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val candidate = URI(value.trim())
            when {
                candidate.isAbsolute -> candidate.toString()
                else -> URI(baseUrl.trim()).resolve(candidate).toString()
            }
        }.getOrNull()
    }

    /** Returns the URL when it uses HTTPS, otherwise null. */
    fun requireHttps(url: String, label: String): String? =
        if (url.startsWith("https://", ignoreCase = true)) url else null.also {
            if (url.isNotBlank()) {
                // caller collects warnings
            }
        }
}
