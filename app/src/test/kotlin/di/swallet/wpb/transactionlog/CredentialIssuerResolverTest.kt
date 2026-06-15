/**
 * Tests credential issuer resolver.
 */

package di.swallet.wpb.transactionlog

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.domain.WalletCredential
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

class CredentialIssuerResolverTest {
    private val resolver = CredentialIssuerResolver(ObjectMapper())

    /**
     * Resolves issuer from an SD-JWT whose iss claim differs from the credential userId.
     * Resolved name and LEI-typed identifier must both use the iss URL.
     */
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

    /**
     * Resolves issuer from a credential with opaque encodedData and no parseable JWT payload.
     * Name must fall back to "Wallet Provider" and identifier must be null.
     */
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
