package di.swallet.wpb.service.format

import di.swallet.wpb.format.sdjwt.SdJwtDisclosureSelector
import di.swallet.wpb.presentation.domain.ClaimPath
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Service responsible for creating selective presentations of SD-JWT credentials.
 * It allows the user to choose which claims to disclose to a Relying Party.
 */
@Service
class PresentationService(
    private val disclosureSelector: SdJwtDisclosureSelector,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Creates a minimized SD-JWT by filtering the original disclosures.
     * @param fullSdJwt The complete SD-JWT string (JWT~Disc1~Disc2~...~)
     * @param requestedClaims Claim names or dot-notation paths (e.g. `address.locality`)
     * @return A new multipart SD-JWT string containing only the selected disclosures.
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