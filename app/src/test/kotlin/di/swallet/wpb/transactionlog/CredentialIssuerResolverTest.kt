package di.swallet.wpb.transactionlog

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.domain.WalletCredential
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

class CredentialIssuerResolverTest {
    private val resolver = CredentialIssuerResolver(ObjectMapper())

    @Test
    fun `resolve uses iss claim from SD-JWT not holder userId`() {
        val iss = "https://pt-mock-issuer.gov.pt"
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"iss":"$iss","sub":"holder-1"}""".toByteArray())
        val credential = WalletCredential(
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "header.$payload.signature",
            encryptedDisclosures = "",
        )

        val resolved = resolver.resolve(credential)

        assertEquals(iss, resolved.name)
        assertEquals(iss, resolved.identifier?.identifier)
        assertEquals("http://data.europa.eu/eudi/id/LEI", resolved.identifier?.type)
    }

    @Test
    fun `resolve falls back to wallet provider when issuer is unknown`() {
        val credential = WalletCredential(
            userId = "holder-1",
            credentialType = "PID",
            encodedData = "opaque",
            encryptedDisclosures = "",
        )

        val resolved = resolver.resolve(credential)

        assertEquals("Wallet Provider", resolved.name)
        assertNull(resolved.identifier)
    }
}
