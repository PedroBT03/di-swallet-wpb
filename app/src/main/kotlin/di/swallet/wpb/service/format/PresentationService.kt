package di.swallet.wpb.service.format

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.util.Base64URL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/**
 * Service responsible for creating selective presentations of SD-JWT credentials.
 * It allows the user to choose which claims to disclose to a Relying Party.
 */
@Service
class PresentationService(private val objectMapper: ObjectMapper) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Creates a minimized SD-JWT by filtering the original disclosures.
     * @param fullSdJwt The complete SD-JWT string (JWT~Disc1~Disc2~...~)
     * @param requestedClaims The list of claim names the user wants to reveal (e.g., ["nationality"])
     * @return A new multipart SD-JWT string containing only the selected disclosures.
     */
    fun createSelectivePresentation(fullSdJwt: String, requestedClaims: List<String>): String {
        // 1. Split the multipart string using the tilde delimiter
        val parts = fullSdJwt.split("~")
        if (parts.size < 2) {
            throw IllegalArgumentException("Invalid SD-JWT format: no disclosures found")
        }

        val signedJwt = parts[0]
        val rawDisclosures = parts.subList(1, parts.size).filter { it.isNotEmpty() }

        val selectedDisclosures = mutableListOf<String>()

        // 2. Iterate through all disclosures to find those that match the requested claims
        for (base64Disclosure in rawDisclosures) {
            try {
                // Decode disclosure: [salt, name, value]
                val jsonArray = String(Base64URL(base64Disclosure).decode())
                val disclosureList = objectMapper.readValue(jsonArray, List::class.java)
                
                val claimName = disclosureList[1] as String
                
                if (requestedClaims.contains(claimName)) {
                    selectedDisclosures.add(base64Disclosure)
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse disclosure, skipping: ${e.message}")
            }
        }

        // 3. Assemble the new presentation string
        val presentation = StringBuilder(signedJwt)
        selectedDisclosures.forEach { presentation.append("~").append(it) }
        presentation.append("~")

        logger.info("Presentation: Created minimized SD-JWT with ${selectedDisclosures.size} revealed claims")
        return presentation.toString()
    }
}