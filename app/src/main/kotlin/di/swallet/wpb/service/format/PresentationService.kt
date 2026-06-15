/**
 * Builds selective SD-JWT presentations by filtering disclosures to requested claim paths.
 */

package di.swallet.wpb.service.format

import di.swallet.wpb.format.sdjwt.SdJwtDisclosureSelector
import di.swallet.wpb.presentation.domain.ClaimPath
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Creates minimized SD-JWT presentations that reveal only the claims requested by a relying party.
 */
@Service
class PresentationService(
    private val disclosureSelector: SdJwtDisclosureSelector,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Filters disclosures from a full SD-JWT to match the requested claim names or dot-notation paths.
     */
    fun createSelectivePresentation(fullSdJwt: String, requestedClaims: List<String>): String {
        val parts = fullSdJwt.split("~")
        if (parts.size < 2) {
            throw IllegalArgumentException("Invalid SD-JWT format: no disclosures found")
        }

        val signedJwt = parts[0]
        val rawDisclosures = parts.subList(1, parts.size).filter { it.isNotEmpty() }
        val paths = requestedClaims.map { name ->
            if (name.contains('.')) ClaimPath.fromDotNotation(name) else ClaimPath.key(name)
        }
        val selectedDisclosures = disclosureSelector.selectDisclosures(rawDisclosures, paths)

        val presentation = StringBuilder(signedJwt)
        selectedDisclosures.forEach { presentation.append("~").append(it) }
        presentation.append("~")

        logger.info("Presentation: Created minimized SD-JWT with ${selectedDisclosures.size} revealed claims")
        return presentation.toString()
    }
}
