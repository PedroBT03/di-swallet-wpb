/**
 * Tests jpa issued credential storage.
 */

package di.swallet.wpb.issuance.storage

import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.testWalletProperties
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.revocation.RevocationTestSupport
import di.swallet.wpb.service.KeyBindingRuntimeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class JpaIssuedCredentialStorageTest {

    /** Returns a disclosure cipher configured with standard test wallet properties. */
    private fun cipher() = DisclosureCipherService(testWalletProperties())
    private val binding = MdocTestSupport.holderBinding()
    private val mdocCodec = MdocTestSupport.stack(holderBindings = listOf(binding)).codec
    private val mdocRegistry = MdocDocTypeRegistry()

    /**
     * SD-JWT payload with two disclosures is split into JWT body plus encrypted disclosure blob; decrypt round-trips the original list.
     */
    @Test
    fun `splits SD-JWT VC into encoded data and encrypted disclosures`() {
        val repo = mock(WalletCredentialRepository::class.java)
        `when`(repo.save(any(WalletCredential::class.java))).thenAnswer { invocation ->
            val arg = invocation.getArgument<WalletCredential>(0)
            WalletCredential(
                id = 100L,
                userId = arg.userId,
                credentialType = arg.credentialType,
                encodedData = arg.encodedData,
                encryptedDisclosures = arg.encryptedDisclosures,
            )
        }

        val storage = JpaIssuedCredentialStorage(
            repo,
            mock(WalletKeyRepository::class.java),
            cipher(),
            mdocCodec,
            mdocRegistry,
            mock(KeyBindingRuntimeService::class.java),
            RevocationTestSupport.credentialStatusParser(),
        )
        val issued = IssuedCredential(
            credentialConfigurationId = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            rawPayload = "issuer-jwt-header.payload.sig~disclosure1~disclosure2~",
        )
        val id = storage.store(holderId = "holder-1", issued = issued)

        assertEquals(100L, id)
        val captor = ArgumentCaptor.forClass(WalletCredential::class.java)
        verify(repo).save(captor.capture())
        val saved = captor.value
        assertEquals("PID", saved.credentialType)
        assertEquals("issuer-jwt-header.payload.sig", saved.encodedData)
        assertTrue(saved.encryptedDisclosures.isNotBlank())

        // round-trip decrypt
        val disclosures = cipher().decrypt(saved.encryptedDisclosures)
        assertEquals(listOf("disclosure1", "disclosure2"), disclosures)
    }

    /**
     * Encoded mDL issuerSigned bytes are stored and retrieved unchanged and pass validateIssuerSigned.
     */
    @Test
    fun `valid mdoc payload is preserved byte-identical in storage and retrieval`() {
        val repo = mock(WalletCredentialRepository::class.java)
        var persisted: WalletCredential? = null
        `when`(repo.save(any(WalletCredential::class.java))).thenAnswer {
            val arg = it.getArgument<WalletCredential>(0)
            persisted = WalletCredential(
                id = 5L,
                userId = arg.userId,
                credentialType = arg.credentialType,
                encodedData = arg.encodedData,
                encryptedDisclosures = arg.encryptedDisclosures,
            )
            persisted
        }
        `when`(repo.findByUserId("holder-2")).thenAnswer { listOfNotNull(persisted) }

        val storage = JpaIssuedCredentialStorage(
            repo,
            mock(WalletKeyRepository::class.java),
            cipher(),
            mdocCodec,
            mdocRegistry,
            mock(KeyBindingRuntimeService::class.java),
            RevocationTestSupport.credentialStatusParser(),
        )
        val issuedPayload = mdocCodec.encode(
            MdocCredentialDocument(
                docType = "org.iso.18013.5.1.mDL",
                namespace = "org.iso.18013.5.1",
                claims = mapOf(
                    "given_name" to "Alice",
                    "driving_privileges" to listOf("B"),
                ),
            ),
            binding.deviceCoseKey,
        )
        val issued = IssuedCredential(
            credentialConfigurationId = "org.iso.18013.5.1.mDL",
            format = IssuanceCredentialFormat.MSO_MDOC,
            rawPayload = issuedPayload,
        )
        val id = storage.store("holder-2", issued)
        assertEquals(5L, id)
        val captor = ArgumentCaptor.forClass(WalletCredential::class.java)
        verify(repo).save(captor.capture())
        val saved = captor.value
        val recovered = repo.findByUserId("holder-2").single()

        assertEquals(issuedPayload, saved.encodedData)
        assertEquals(issuedPayload, recovered.encodedData)
        assertTrue(
            java.util.Base64.getUrlDecoder().decode(issuedPayload)
                .contentEquals(java.util.Base64.getUrlDecoder().decode(saved.encodedData)),
        )
        assertTrue(mdocCodec.validateIssuerSigned(saved.encodedData))
    }

    /**
     * Non-CBOR rawPayload for mdoc format throws IllegalArgumentException instead of being silently rebuilt.
     */
    @Test
    fun `invalid mdoc payload is rejected instead of silently rebuilt`() {
        val repo = mock(WalletCredentialRepository::class.java)
        val storage = JpaIssuedCredentialStorage(
            repo,
            mock(WalletKeyRepository::class.java),
            cipher(),
            mdocCodec,
            mdocRegistry,
            mock(KeyBindingRuntimeService::class.java),
            RevocationTestSupport.credentialStatusParser(),
        )
        val issued = IssuedCredential(
            credentialConfigurationId = "org.iso.18013.5.1.mDL",
            format = IssuanceCredentialFormat.MSO_MDOC,
            rawPayload = "not-a-valid-cbor-mdoc",
        )
        assertThrows(IllegalArgumentException::class.java) {
            storage.store("holder-x", issued)
        }
    }
}
