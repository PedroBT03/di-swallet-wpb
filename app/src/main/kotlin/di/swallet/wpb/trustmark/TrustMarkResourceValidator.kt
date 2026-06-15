/**
 * Validates fetched TrustMarkResource payloads before they are shown in the wallet UI.
 */

package di.swallet.wpb.trustmark

import org.springframework.stereotype.Component

/** Checks required TrustMarkResource fields and collects non-fatal warnings. */
@Component
class TrustMarkResourceValidator {
    /** Validates image URL and localized text requirements for a TrustMarkResource payload. */
    fun validate(resource: TrustMarkResourcePayload): ValidationResult {
        val warnings = mutableListOf<String>()
        val imageUrl = resource.image?.url?.trim()
        if (imageUrl.isNullOrBlank()) {
            return ValidationResult(valid = false, warnings = listOf("TrustMarkResource.image.url is required"))
        }
        if (resource.image?.name.isNullOrBlank()) {
            warnings.add("TrustMarkResource.image.name is missing")
        }
        if (resource.text?.localizations.isNullOrEmpty()) {
            return ValidationResult(
                valid = false,
                warnings = warnings + "TrustMarkResource.text.localizations is required",
            )
        }
        if (resource.text?.name.isNullOrBlank()) {
            warnings.add("TrustMarkResource.text.name is missing")
        }
        return ValidationResult(valid = true, warnings = warnings)
    }

    /** Outcome of TrustMarkResource validation with optional warnings. */
    data class ValidationResult(
        val valid: Boolean,
        val warnings: List<String> = emptyList(),
    )
}
