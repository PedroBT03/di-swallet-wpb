package di.swallet.wpb.consent

import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class IssuedCredentialPreviewParserTest {

    private val parser = IssuedCredentialPreviewParser()

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

    @Test
    fun `mdoc preview is unavailable in mvp`() {
        val issued = IssuedCredential(
            credentialConfigurationId = "mdl",
            format = IssuanceCredentialFormat.MSO_MDOC,
            rawPayload = "mdoc-bytes",
        )
        val preview = parser.parse(issued, deviceBound = false)
        assertEquals(1, preview.size)
        assertFalse(preview.single().previewAvailable)
    }
}
