package di.swallet.wpb.presentation.trust

import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.presentation.domain.TrustDecisionMode
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.trust.core.TrustBindingRule
import di.swallet.wpb.trust.core.TrustSnapshot
import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import di.swallet.wpb.trust.core.TrustSnapshotResolver
import di.swallet.wpb.trust.core.TrustedEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.security.cert.X509Certificate
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

    private fun validator(
        properties: OpenId4VpProperties,
        availability: TrustSnapshotAvailability = TrustSnapshotAvailability.Unavailable("no_snapshot"),
        validationResult: AccessCertificateValidationResult = AccessCertificateValidationResult.Trusted,
    ): DefaultTrustValidator {
        val resolver = object : TrustSnapshotResolver {
            override fun currentAvailability(): TrustSnapshotAvailability = availability
        }
        val extractor = object : VerifierCertificateExtractor {
            override fun extract(request: ResolvedAuthorizationRequest): VerifierCertificateMaterial? {
                val cert = mock(X509Certificate::class.java)
                return VerifierCertificateMaterial(chain = listOf(cert), leaf = cert)
            }
        }
        val validation = object : AccessCertificateValidationService {
            override fun validate(
                requestClientId: String,
                material: VerifierCertificateMaterial,
                snapshot: TrustSnapshot,
            ): AccessCertificateValidationResult = validationResult
        }
        return DefaultTrustValidator(properties, resolver, extractor, validation)
    }

    private fun properties(
        demoMode: Boolean,
        allowedClientIds: String = "",
        allowFailOpenInDemo: Boolean = true,
    ): OpenId4VpProperties = OpenId4VpProperties().apply {
        this.demoMode = demoMode
        this.trust.allowedClientIds = allowedClientIds
        this.trust.allowFailOpenInDemoMode = allowFailOpenInDemo
    }

    @Test
    fun `fail-open in demo mode when trust source unavailable`() {
        val validator = validator(properties(demoMode = true))
        val result = validator.validate(context("redirect_uri:http://callback"))
        assertTrue(result.trustDecision?.trusted == true)
        assertEquals(TrustDecisionMode.DEGRADED_DEMO_OPEN, result.trustDecision?.mode)
        assertTrue(result.trustDecision?.reason?.contains("fail-open") == true)
    }

    @Test
    fun `rejects unsupported prefix`() {
        val validator = validator(properties(demoMode = true))
        val result = validator.validate(context("urn-unknown:foo"))
        assertFalse(result.trustDecision?.trusted == true)
    }

    @Test
    fun `fail-closed outside demo mode when trust source unavailable`() {
        val validator = validator(properties(demoMode = false))
        val result = validator.validate(context("verifier-demo-client"))
        assertFalse(result.trustDecision?.trusted == true)
        assertTrue(result.trustDecision?.reason?.contains("Trust source unavailable") == true)
    }

    @Test
    fun `enforces allow list as secondary control`() {
        val snapshot = TrustSnapshot(
            trustAnchors = emptyList(),
            entities = mapOf(
                "verifier-demo-client" to TrustedEntity(
                    entityId = "verifier-demo-client",
                    bindings = setOf(TrustBindingRule("client_id", "verifier-demo-client")),
                ),
                "unknown-verifier" to TrustedEntity(
                    entityId = "unknown-verifier",
                    bindings = setOf(TrustBindingRule("client_id", "unknown-verifier")),
                ),
            ),
            source = "test",
            loadedAt = Instant.now(),
        )
        val validator = validator(
            properties = properties(demoMode = false, allowedClientIds = "verifier-demo-client"),
            availability = TrustSnapshotAvailability.Available(snapshot),
        )
        val allowed = validator.validate(context("verifier-demo-client"))
        val denied = validator.validate(context("unknown-verifier"))
        assertTrue(allowed.trustDecision?.trusted == true)
        assertFalse(denied.trustDecision?.trusted == true)
    }

    @Test
    fun `rejects when certificate validation fails`() {
        val snapshot = TrustSnapshot(
            trustAnchors = emptyList(),
            entities = mapOf(
                "verifier-demo-client" to TrustedEntity(
                    entityId = "verifier-demo-client",
                    bindings = setOf(TrustBindingRule("client_id", "verifier-demo-client")),
                ),
            ),
            source = "test",
            loadedAt = Instant.now(),
        )
        val validator = validator(
            properties = properties(demoMode = false),
            availability = TrustSnapshotAvailability.Available(snapshot),
            validationResult = AccessCertificateValidationResult.Rejected("pkix failed"),
        )
        val result = validator.validate(context("verifier-demo-client"))
        assertFalse(result.trustDecision?.trusted == true)
        assertEquals("pkix failed", result.trustDecision?.reason)
    }
}
