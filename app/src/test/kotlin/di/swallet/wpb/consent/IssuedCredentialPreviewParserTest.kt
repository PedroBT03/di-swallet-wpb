/**
 * Tests issued credential preview parser.
 */

package di.swallet.wpb.consent

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.service.DemoAttestationClaims
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class IssuedCredentialPreviewParserTest {

    private val sdJwtService = SdJwtService(ObjectMapper())
    private val mdocStack = MdocTestSupport.stack(holderBindings = listOf(MdocTestSupport.holderBinding()))
    private val parser = IssuedCredentialPreviewParser(sdJwtService, mdocStack.codec)

    /**
     * Feeds an SD-JWT payload with one base64 disclosure for given_name=Alice and expects
     * parse to return a single preview entry with that claim name and value.
     */
    @Test
    fun `parses sd-jwt disclosures into claim preview`() {
        val disclosure = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""["salt","given_name","Alice"]""".toByteArray())
        val issued = IssuedCredential(
            credentialConfigurationId = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            rawPayload = "header.payload.sig~$disclosure~",
        )

        val preview = parser.parse(issued, deviceBound = true)
        assertEquals(1, preview.size)
        assertEquals("given_name", preview.single().name)
        assertEquals("Alice", preview.single().value)
    }

    /**
     * Nested address and place_of_birth objects show qualified leaf names, not _sd containers.
     */
    @Test
    fun `parses nested sd-jwt objects with qualified claim names`() {
        val issuedDisclosures = sdJwtService.disclosuresFromClaimMap(
            mapOf(
                "place_of_birth" to mapOf(
                    "country" to "PT",
                    "locality" to "Lisbon",
                ),
                "address" to mapOf(
                    "locality" to "Lisbon",
                    "country" to "PT",
                ),
            ),
        )
        val payload = buildString {
            append("header.payload.sig")
            issuedDisclosures.disclosures.forEach { disclosure ->
                append('~').append(disclosure)
            }
            append('~')
        }
        val issued = IssuedCredential(
            credentialConfigurationId = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            rawPayload = payload,
        )

        val preview = parser.parse(issued, deviceBound = true)
        val byName = preview.associate { it.name to it.value }

        assertEquals("PT", byName["place_of_birth.country"])
        assertEquals("Lisbon", byName["place_of_birth.locality"])
        assertEquals("Lisbon", byName["address.locality"])
        assertEquals("PT", byName["address.country"])
        assertFalse(byName.containsKey("place_of_birth"))
        assertFalse(byName.containsKey("address"))
        assertFalse(byName.containsKey("locality"))
        assertFalse(byName.containsKey("country"))
    }

    /**
     * Demo PID claim map preview matches CIR field names without SD container noise.
     */
    @Test
    fun `parses demo pid claim map disclosures`() {
        val issuedDisclosures = sdJwtService.disclosuresFromClaimMap(DemoAttestationClaims.pidClaims("holder-1"))
        val payload = buildString {
            append("header.payload.sig")
            issuedDisclosures.disclosures.forEach { disclosure ->
                append('~').append(disclosure)
            }
            append('~')
        }
        val issued = IssuedCredential(
            credentialConfigurationId = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            rawPayload = payload,
        )

        val preview = parser.parse(issued, deviceBound = true)
        val byName = preview.associate { it.name to it.value }

        assertEquals("Pedro", byName["given_name"])
        assertEquals("PT", byName["place_of_birth.country"])
        assertEquals("Lisbon", byName["place_of_birth.locality"])
        assertEquals("Lisbon", byName["address.locality"])
        assertEquals("PT", byName["address.country"])
        assertEquals("PT, ES", byName["nationalities"])
    }

    /**
     * Parses an MSO mdoc credential into namespace claim names and values.
     */
    @Test
    fun `parses mdoc claims into claim preview`() {
        val binding = MdocTestSupport.holderBinding("mdl-preview-key")
        val stack = MdocTestSupport.stack(holderBindings = listOf(binding))
        val parserWithMdoc = IssuedCredentialPreviewParser(sdJwtService, stack.codec)
        val claims = DemoAttestationClaims.mdlClaims()
        val rawPayload = stack.codec.encode(
            MdocCredentialDocument(
                docType = "org.iso.18013.5.1.mDL",
                namespace = "org.iso.18013.5.1",
                claims = claims,
            ),
            binding.deviceCoseKey,
        )
        val issued = IssuedCredential(
            credentialConfigurationId = "mdoc_mdl",
            format = IssuanceCredentialFormat.MSO_MDOC,
            rawPayload = rawPayload,
        )

        val preview = parserWithMdoc.parse(issued, deviceBound = true)
        val byName = preview.associate { it.name to it.value }

        assertEquals("Pedro", byName["given_name"])
        assertEquals("Tavares", byName["family_name"])
        assertEquals("2000-01-01", byName["birth_date"])
        assertEquals("B", byName["driving_privileges"])
        assertEquals("PT", byName["issuing_country"])
        assertTrue(preview.all { it.previewAvailable })
    }

    /**
     * Invalid mdoc bytes yield an empty preview list.
     */
    @Test
    fun `mdoc preview is empty when payload cannot be decoded`() {
        val issued = IssuedCredential(
            credentialConfigurationId = "mdl",
            format = IssuanceCredentialFormat.MSO_MDOC,
            rawPayload = "not-valid-mdoc",
        )
        val preview = parser.parse(issued, deviceBound = false)
        assertTrue(preview.isEmpty())
    }
}
