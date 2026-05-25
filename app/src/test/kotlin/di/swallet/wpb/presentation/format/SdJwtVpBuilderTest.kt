package di.swallet.wpb.presentation.format

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.service.format.SdJwtService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.Optional

class SdJwtVpBuilderTest {

    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService()

    private class CapturingSigner : KeyBindingJwtSigner {
        var lastUserId: String? = null
        var lastPayload: Map<String, Any>? = null
        override fun signKeyBindingJwt(userId: String, payload: Map<String, Any>): String {
            lastUserId = userId
            lastPayload = payload
            return "KB.JWT.SIG"
        }
    }

    @Test
    fun `builds SD-JWT VP with only requested disclosures and a KB-JWT`() {
        val disclosureGiven = sdJwtService.createDisclosure("given_name", "Pedro")
        val disclosureCountry = sdJwtService.createDisclosure("country", "PT")
        val storedJwt = "HEAD.PAYLOAD.SIG"

        val credential = WalletCredential(
            id = 100L,
            userId = "holder-1",
            credentialType = "PID",
            encodedData = storedJwt,
            encryptedDisclosures = "ignored",
        )
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findById(100L)).thenReturn(Optional.of(credential))

        val cipher = mock(DisclosureCipherService::class.java)
        `when`(cipher.decrypt(anyString())).thenReturn(listOf(disclosureGiven, disclosureCountry))

        val signer = CapturingSigner()
        val builder = SdJwtVpBuilder(repository, cipher, signer, objectMapper)

        val selected = SelectedCredential(
            candidateId = "c",
            credentialId = 100L,
            holderId = "holder-1",
            queryId = "pid",
            credentialType = "PID",
            format = CredentialFormat.SD_JWT,
            requestedClaims = listOf("given_name"),
        )

        val result = builder.build(selected, "verifier-demo-client", "n-123")
        assertFalse(result.isDemo)
        assertEquals(1, result.disclosuresIncluded)
        assertTrue(result.presentation.startsWith("$storedJwt~"))
        assertTrue(result.presentation.contains(disclosureGiven))
        assertFalse(result.presentation.contains(disclosureCountry))
        assertTrue(result.presentation.endsWith("~KB.JWT.SIG"))
        assertEquals("holder-1", signer.lastUserId)
        assertEquals("verifier-demo-client", signer.lastPayload?.get("aud"))
        assertEquals("n-123", signer.lastPayload?.get("nonce"))
        assertNotNull(signer.lastPayload?.get("sd_hash"))
    }

    @Test
    fun `includes all disclosures when no specific claims are requested`() {
        val disclosureA = sdJwtService.createDisclosure("a", "1")
        val disclosureB = sdJwtService.createDisclosure("b", "2")
        val credential = WalletCredential(
            id = 1L,
            userId = "holder",
            credentialType = "PID",
            encodedData = "HEAD.PAYLOAD.SIG",
            encryptedDisclosures = "ignored",
        )
        val repository = mock(WalletCredentialRepository::class.java)
        `when`(repository.findById(1L)).thenReturn(Optional.of(credential))
        val cipher = mock(DisclosureCipherService::class.java)
        `when`(cipher.decrypt(anyString())).thenReturn(listOf(disclosureA, disclosureB))

        val builder = SdJwtVpBuilder(repository, cipher, CapturingSigner(), objectMapper)
        val selected = SelectedCredential(
            candidateId = "c",
            credentialId = 1L,
            holderId = "holder",
            queryId = "q",
            credentialType = "PID",
            format = CredentialFormat.SD_JWT,
            requestedClaims = emptyList(),
        )
        val result = builder.build(selected, "verifier", "n")
        assertEquals(2, result.disclosuresIncluded)
    }

    @Test
    fun `demo fallback marks output for synthetic candidates`() {
        val builder = SdJwtVpBuilder(
            walletCredentialRepository = mock(WalletCredentialRepository::class.java),
            disclosureCipherService = mock(DisclosureCipherService::class.java),
            keyBindingJwtSigner = CapturingSigner(),
            objectMapper = objectMapper,
        )
        val selected = SelectedCredential(
            candidateId = "demo:q",
            credentialId = null,
            holderId = "demo-holder",
            queryId = "q",
            credentialType = "DemoCredential",
            format = CredentialFormat.SD_JWT,
        )
        val result = builder.build(selected, "verifier-demo-client", "n-1")
        assertTrue(result.isDemo)
        assertTrue(result.presentation.startsWith("demo-vp:"))
    }
}
