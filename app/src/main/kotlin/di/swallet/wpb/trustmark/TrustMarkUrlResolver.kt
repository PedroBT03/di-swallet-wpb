package di.swallet.wpb.trustmark

import org.springframework.stereotype.Component
import java.net.URI

@Component
class TrustMarkUrlResolver {
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

    fun requireHttps(url: String, label: String): String? =
        if (url.startsWith("https://", ignoreCase = true)) url else null.also {
            if (url.isNotBlank()) {
                // caller collects warnings
            }
        }
}
