package di.swallet.wpb.presentation.trust

import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DefaultTrustValidatorTest {

    private fun context(clientId: String): PresentationContext {
        val now = Instant.now()
        return PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
                correlationId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(120),
            ),
            state = PresentationState.REQUEST_RESOLVED,
            authorizationRequest = ResolvedAuthorizationRequest(
                requestToken = "rt",
                requestUri = "http://verifier",
                clientId = clientId,
                responseMode = PresentationResponseMode.DIRECT_POST,
                nonce = "n",
                state = "s",
                requirements = PresentationRequirements(dcqlQueryJson = "{}", credentialQueryIds = emptyList()),
            ),
        )
    }

    @Test
    fun `accepts known prefix without configured trust list`() {
        val validator = DefaultTrustValidator(allowedClientIdsCsv = "", demoMode = false)
        val result = validator.validate(context("redirect_uri:http://callback"))
        assertTrue(result.trustDecision?.trusted == true)
        assertEquals("redirect_uri", result.verifierIdentity?.clientIdPrefix)
    }

    @Test
    fun `rejects unsupported prefix`() {
        val validator = DefaultTrustValidator(allowedClientIdsCsv = "", demoMode = false)
        val result = validator.validate(context("urn-unknown:foo"))
        assertFalse(result.trustDecision?.trusted == true)
    }

    @Test
    fun `enforces allow list when configured`() {
        val validator = DefaultTrustValidator(
            allowedClientIdsCsv = "verifier-demo-client",
            demoMode = false,
        )
        val allowed = validator.validate(context("verifier-demo-client"))
        val denied = validator.validate(context("unknown-verifier"))
        assertTrue(allowed.trustDecision?.trusted == true)
        assertFalse(denied.trustDecision?.trusted == true)
    }

    @Test
    fun `demo mode bypasses allow list`() {
        val validator = DefaultTrustValidator(
            allowedClientIdsCsv = "only-this",
            demoMode = true,
        )
        val result = validator.validate(context("verifier-demo-client"))
        assertTrue(result.trustDecision?.trusted == true)
    }
}
