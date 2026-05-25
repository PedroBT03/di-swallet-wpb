package di.swallet.wpb.issuance.storage

import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.service.format.DisclosureCipherService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class JpaIssuedCredentialStorageTest {

    private fun cipher() = DisclosureCipherService(WalletProperties())

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

        val storage = JpaIssuedCredentialStorage(repo, cipher())
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
        assertEquals("pid_jwt", saved.credentialType)
        assertEquals("issuer-jwt-header.payload.sig", saved.encodedData)
        assertTrue(saved.encryptedDisclosures.isNotBlank())

        // round-trip decrypt
        val disclosures = cipher().decrypt(saved.encryptedDisclosures)
        assertEquals(listOf("disclosure1", "disclosure2"), disclosures)
    }

    @Test
    fun `non SD-JWT credential is persisted with raw payload and empty disclosures`() {
        val repo = mock(WalletCredentialRepository::class.java)
        `when`(repo.save(any(WalletCredential::class.java))).thenAnswer {
            val arg = it.getArgument<WalletCredential>(0)
            WalletCredential(
                id = 5L,
                userId = arg.userId,
                credentialType = arg.credentialType,
                encodedData = arg.encodedData,
                encryptedDisclosures = arg.encryptedDisclosures,
            )
        }

        val storage = JpaIssuedCredentialStorage(repo, cipher())
        val issued = IssuedCredential(
            credentialConfigurationId = "driver_license",
            format = IssuanceCredentialFormat.MSO_MDOC,
            rawPayload = "binary-mdoc-payload",
        )
        val id = storage.store("holder-2", issued)
        assertEquals(5L, id)
    }
}
