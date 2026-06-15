/**
 * End-to-end tests for issuer signed mdoc binding.
 */

package di.swallet.wpb.presentation

import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.testWalletProperties
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.storage.JpaIssuedCredentialStorage
import di.swallet.wpb.format.mdoc.IndependentMdocVerifier
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocTestSupport
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.presentation.format.MdocVpBuilder
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.service.KeyBindingRuntimeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong

class IssuerSignedMdocBindingE2ETest {
    private val binding = MdocTestSupport.holderBinding()
    private val stack = MdocTestSupport.stack(holderBindings = listOf(binding))
    private val codec = stack.codec
    private val registry = MdocDocTypeRegistry()
    private val independent = IndependentMdocVerifier()

    /**
     * PID mdoc is issued through storage then selected for VP building.
     * Stored encodedData matches the issued artifact and the presented device response preserves the issuer-auth payload.
     */
    @Test
    fun `issuer artifact is preserved from issuance storage to presentation`() {
        val state = mutableListOf<WalletCredential>()
        val repository = mock(WalletCredentialRepository::class.java)
        val sequence = AtomicLong(0)
        doAnswer { invocation ->
            val incoming = invocation.getArgument<WalletCredential>(0)
            val persisted = WalletCredential(
                id = sequence.incrementAndGet(),
                userId = incoming.userId,
                credentialType = incoming.credentialType,
                encodedData = incoming.encodedData,
                encryptedDisclosures = incoming.encryptedDisclosures,
                walletKey = incoming.walletKey,
                issuedAt = incoming.issuedAt,
            )
            state += persisted
            persisted
        }.`when`(repository).save(any(WalletCredential::class.java))
        `when`(repository.findById(any(Long::class.java))).thenAnswer {
            val id = it.getArgument<Long>(0)
            Optional.ofNullable(state.firstOrNull { credential -> credential.id == id })
        }

        val issuedPayload = codec.encode(
            MdocCredentialDocument(
                docType = "eu.europa.ec.eudi.pid.1",
                namespace = "eu.europa.ec.eudi.pid.1",
                claims = mapOf(
                    "given_name" to "Alice",
                    "family_name" to "Doe",
                ),
            ),
            binding.deviceCoseKey,
        )

        val storage = JpaIssuedCredentialStorage(
            repository = repository,
            walletKeyRepository = stack.walletKeyRepository,
            disclosureCipher = DisclosureCipherService(testWalletProperties()),
            mdocCredentialCodec = codec,
            mdocDocTypeRegistry = registry,
            keyBindingRuntimeService = mock(KeyBindingRuntimeService::class.java),
            credentialStatusParser = di.swallet.wpb.revocation.RevocationTestSupport.credentialStatusParser(),
        )
        val credentialId = storage.store(
            holderId = "holder-1",
            issued = IssuedCredential(
                credentialConfigurationId = "eu.europa.ec.eudi.pid.1",
                format = IssuanceCredentialFormat.MSO_MDOC,
                rawPayload = issuedPayload,
            ),
            walletKey = binding.walletKey,
        )

        val vpBuilder = MdocVpBuilder(
            walletCredentialRepository = repository,
            mdocCredentialCodec = codec,
            mdocDocTypeRegistry = registry,
        )
        val vp = vpBuilder.build(
            selected = SelectedCredential(
                candidateId = "candidate-1",
                credentialId = credentialId,
                holderId = "holder-1",
                queryId = "pid",
                credentialType = "eu.europa.ec.eudi.pid.1",
                format = CredentialFormat.MDOC,
                requestedClaimPaths = listOf(di.swallet.wpb.presentation.domain.ClaimPath.key("given_name")),
            ),
            handover = MdocTestSupport.handover(),
        )

        val storedPayload = state.single().encodedData
        assertEquals(issuedPayload, storedPayload)

        val issuedMsoPayload = independent.extractIssuerAuthPayload(issuedPayload)
        val presentedMsoPayload = independent.extractIssuerAuthPayloadFromDeviceResponse(vp.presentation)
        assertTrue(issuedMsoPayload.contentEquals(presentedMsoPayload))
        assertTrue(independent.verifyDeviceResponse(vp.presentation))
    }
}
