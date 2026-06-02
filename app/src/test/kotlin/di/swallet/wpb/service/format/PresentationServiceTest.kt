package di.swallet.wpb.service.format

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.format.sdjwt.SdJwtService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PresentationServiceTest {

    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService()
    private val presentationService = PresentationService(objectMapper)

    /**
     * Verifies that the presentation only includes requested disclosures.
     */
    @Test
    fun `should filter disclosures for selective presentation`() {
        // Setup: Create a multipart SD-JWT with 2 claims
        val disc1 = sdJwtService.createDisclosure("name", "Pedro")
        val disc2 = sdJwtService.createDisclosure("country", "PT")
        val fullSdJwt = "HEADER.PAYLOAD.SIG~$disc1~$disc2~"

        // Execute: Request only the 'country'
        val presentation = presentationService.createSelectivePresentation(fullSdJwt, listOf("country"))

        // Assert: JWT and disc2 must be there, disc1 must be gone
        assertTrue(presentation.contains("HEADER.PAYLOAD.SIG"))
        assertTrue(presentation.contains(disc2))
        assertFalse(presentation.contains(disc1))
        assertTrue(presentation.endsWith("~"))
    }
}