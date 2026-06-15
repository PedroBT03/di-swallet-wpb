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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.util.Optional

class ChallengeServiceTest {

    private val repository = Mockito.mock(Fido2AssertionChallengeRepository::class.java)
    private val challengeService = ChallengeService(
        walletProperties = WalletProperties(challenge = WalletProperties.ChallengeProperties(ttlSeconds = 120)),
        challengeRepository = repository,
    )

    @Test
    fun `challenge request should be single use only`() {
        val userId = "security-test-user"
        val options = PublicKeyCredentialRequestOptions.builder()
            .challenge(ByteArray("nonce-123".toByteArray()))
            .rpId("localhost")
            .build()
        val dummyRequest = AssertionRequest.builder()
            .publicKeyCredentialRequestOptions(options)
            .username(Optional.of(userId))
            .build()
        val challengeKey = options.challenge.base64Url
        val encoded = AssertionRequestCodec.encode(dummyRequest)

        Mockito.`when`(repository.save(any())).thenAnswer { it.arguments[0] }
        Mockito.`when`(repository.findByUserIdAndChallengeKey(userId, challengeKey))
            .thenReturn(
                Optional.of(
                    di.swallet.wpb.domain.Fido2AssertionChallenge(
                        userId = userId,
                        challengeKey = challengeKey,
                        requestJson = encoded,
                        expiresAtEpochMillis = System.currentTimeMillis() + 120_000L,
                    ),
                ),
            )

        challengeService.storeRequest(userId, dummyRequest)
        val rawChallenge = challengeService.getRawChallenge(userId, challengeKey)
        assertNotNull(rawChallenge)

        val retrieved = challengeService.getRequest(userId, challengeKey)
        assertNotNull(retrieved)
        assertEquals(dummyRequest, retrieved)

        challengeService.removeRequest(userId, challengeKey)
        Mockito.`when`(repository.findByUserIdAndChallengeKey(userId, challengeKey))
            .thenReturn(Optional.empty())
        assertNull(challengeService.getRequest(userId, challengeKey))
        assertNull(challengeService.getRawChallenge(userId, challengeKey))
    }
}
