package di.swallet.wpb.security

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.domain.Fido2AssertionChallengeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.Optional

@DataJpaTest
@ActiveProfiles("test")
@Import(ChallengeService::class, WalletProperties::class)
class ChallengeServicePersistenceTest {

    @Autowired
    private lateinit var challengeService: ChallengeService

    @Autowired
    private lateinit var challengeRepository: Fido2AssertionChallengeRepository

    @Test
    fun `stores and consumes challenge by challenge key`() {
        val userId = "holder-a"
        val request = assertionRequest(userId, "challenge-one")
        challengeService.storeRequest(userId, request)

        val challengeKey = request.publicKeyCredentialRequestOptions.challenge.base64Url
        val retrieved = challengeService.getRequest(userId, challengeKey)
        assertNotNull(retrieved)
        assertEquals(request, retrieved)

        challengeService.removeRequest(userId, challengeKey)
        assertNull(challengeService.getRequest(userId, challengeKey))
    }

    @Test
    fun `parallel challenges for same user do not replace each other`() {
        val userId = "holder-b"
        val first = assertionRequest(userId, "challenge-first")
        val second = assertionRequest(userId, "challenge-second")
        challengeService.storeRequest(userId, first)
        challengeService.storeRequest(userId, second)

        assertEquals(2, challengeRepository.count())

        val firstKey = first.publicKeyCredentialRequestOptions.challenge.base64Url
        val secondKey = second.publicKeyCredentialRequestOptions.challenge.base64Url
        assertNotNull(challengeService.getRequest(userId, firstKey))
        assertNotNull(challengeService.getRequest(userId, secondKey))
    }

    private fun assertionRequest(userId: String, challengeLabel: String): AssertionRequest {
        val options = PublicKeyCredentialRequestOptions.builder()
            .challenge(ByteArray(challengeLabel.toByteArray()))
            .rpId("localhost")
            .build()
        return AssertionRequest.builder()
            .publicKeyCredentialRequestOptions(options)
            .username(Optional.of(userId))
            .build()
    }
}
