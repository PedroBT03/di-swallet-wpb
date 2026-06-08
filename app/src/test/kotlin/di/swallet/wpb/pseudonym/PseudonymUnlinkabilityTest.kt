package di.swallet.wpb.pseudonym

import di.swallet.wpb.config.PseudonymProperties
import di.swallet.wpb.security.Fido2TestHelper
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.transactionlog.service.TransactionLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isA
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID

class PseudonymUnlinkabilityTest {
    private val properties = PseudonymProperties().apply { enabled = true }
    private val repository = mock(PseudonymCredentialRepository::class.java)
    private val hsmService = mock(HsmService::class.java)
    private val transactionLogger = mock(TransactionLogger::class.java)
    private val challengeStore = PseudonymChallengeStore()
    private lateinit var service: PseudonymService
    private val keyPairA = Fido2TestHelper.generateDeviceKeyPair()
    private val keyPairB = Fido2TestHelper.generateDeviceKeyPair()

    @BeforeEach
    fun setUp() {
        service = PseudonymService(
            properties = properties,
            repository = repository,
            rpIdPolicy = RpIdPolicy(properties),
            challengeStore = challengeStore,
            hsmService = hsmService,
            coseKeyMaterial = PseudonymCoseKeyMaterial(hsmService),
            transactionMapper = PseudonymTransactionMapper(properties),
            transactionLogger = transactionLogger,
        )
        `when`(repository.countByHolderIdAndRpId(anyString(), anyString())).thenReturn(0L)
    }

    @Test
    fun `same holder different rps produce unlinkable passkeys`() {
        val holderId = "holder-1"
        val rpA = "shop.example.com"
        val rpB = "bank.example.com"
        val idA = UUID.randomUUID()
        val idB = UUID.randomUUID()
        val credA = pendingCredential(idA, holderId, rpA, "handle-a")
        val credB = pendingCredential(idB, holderId, rpB, "handle-b")

        `when`(repository.findById(idA)).thenReturn(java.util.Optional.of(credA))
        `when`(repository.findById(idB)).thenReturn(java.util.Optional.of(credB))
        `when`(repository.save(isA(PseudonymCredential::class.java))).thenAnswer { it.arguments[0] as PseudonymCredential }
        `when`(hsmService.generateDedicatedEcKey("pseudonym-$idA"))
            .thenReturn(keyPairA.public as ECPublicKey)
        `when`(hsmService.generateDedicatedEcKey("pseudonym-$idB"))
            .thenReturn(keyPairB.public as ECPublicKey)
        `when`(hsmService.getDedicatedPublicKey("pseudonym-$idA"))
            .thenReturn(keyPairA.public as ECPublicKey)
        `when`(hsmService.getDedicatedPublicKey("pseudonym-$idB"))
            .thenReturn(keyPairB.public as ECPublicKey)

        val regA = register(idA, holderId, rpA)
        val regB = register(idB, holderId, rpB)

        assertNotEquals(credA.userHandle, credB.userHandle)
        assertNotEquals(regA.credentialId, regB.credentialId)
        assertNotEquals(regA.publicKeyCose, regB.publicKeyCose)
        assertEquals("pseudonym-$idA", credA.keyAlias)
        assertEquals("pseudonym-$idB", credB.keyAlias)
    }

    private fun pendingCredential(id: UUID, holderId: String, rpId: String, userHandle: String): PseudonymCredential =
        PseudonymCredential(
            id = id,
            holderId = holderId,
            rpId = rpId,
            userHandle = userHandle,
        )

    private fun register(id: UUID, holderId: String, rpId: String): RegistrationFinishResponse {
        val origin = "https://$rpId"
        val options = service.registrationOptions(id, holderId, RegistrationOptionsRequest(holderId, origin))
        val clientData = Base64.getUrlEncoder().withoutPadding().encodeToString(
            PseudonymWebAuthnCodec.buildClientDataJson("webauthn.create", options.challenge, origin).toByteArray(),
        )
        return service.finishRegistration(
            id,
            holderId,
            RegistrationFinishRequest(holderId = holderId, origin = origin, clientDataJSON = clientData),
        )
    }
}
