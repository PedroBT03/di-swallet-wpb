/**
 * Integration test for bundled demo LoTE + verifier access certificate fixtures.
 */

package di.swallet.wpb.presentation.trust

import di.swallet.wpb.config.OpsProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationRequirements
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.presentation.domain.TrustDecisionMode
import di.swallet.wpb.trust.lote.LoteTrustParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant
import java.util.UUID

class DemoTrustMaterialPkixTest {

    @Test
    fun `bundled demo verifier access certificate passes pkix trust validation`() {
        val props = OpenId4VpProperties().apply {
            demoMode = true
            trust.sourceMode = "hybrid"
            trust.localVerifiersPath = "classpath:trust/demo-lote.json"
            trust.localTrustAnchorPemPaths = "classpath:trust/demo-anchor.pem"
            trust.allowedClientIds = "verifier-demo-client"
            trust.allowFailOpenInDemoMode = false
        }
        val loader = DefaultResourceLoader()
        val chainValidator = CertificateChainValidator(loader)
        val snapshotService = TrustSnapshotService(props, OpsProperties(), chainValidator, LoteTrustParser())
        val extractor = DefaultVerifierCertificateExtractor()
        val validator = DefaultTrustValidator(
            properties = props,
            trustSnapshotResolver = snapshotService,
            certificateExtractor = extractor,
            certificateValidationService = PkixAccessCertificateValidationService(chainValidator),
        )

        val accessPem = loader.getResource("classpath:trust/demo-verifier-access.pem")
            .inputStream
            .bufferedReader()
            .readText()
        val x5c = accessPem
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
        val verifierInfoJson = """{"x5c":["$x5c"]}"""

        val now = Instant.now()
        val context = PresentationContext(
            sessionMeta = SessionMetadata(
                sessionId = UUID.randomUUID(),
                correlationId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(120),
            ),
            state = PresentationState.REQUEST_RESOLVED,
            authorizationRequest = ResolvedAuthorizationRequest(
                requestToken = "rt-demo",
                requestUri = "http://localhost:8081/request/conformance/simple_claim.json",
                clientId = "verifier-demo-client",
                responseMode = PresentationResponseMode.DIRECT_POST,
                nonce = "nonce-demo",
                state = "state-demo",
                responseUri = "http://localhost:8081/direct_post",
                verifierDisplayName = "verifier-demo-client",
                requirements = PresentationRequirements(
                    dcqlQueryJson = """{"credentials":[{"id":"pid","format":"vc+sd-jwt","claims":[{"path":["given_name"]}]}]}""",
                    credentialQueryIds = listOf("pid"),
                ),
                verifierInfoJson = verifierInfoJson,
            ),
        )

        val result = validator.validate(context)
        assertTrue(result.trustDecision?.trusted == true, result.trustDecision?.reason)
        assertEquals(TrustDecisionMode.TRUSTED, result.trustDecision?.mode)
        assertEquals(
            "A20F7A1C0E99AF7BF512955DAB6C80273E78BC87AD81B4BC85EB9AFBFED4527D",
            TrustTestCertificates.sha256Hex(
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(accessPem.byteInputStream()) as java.security.cert.X509Certificate,
            ),
        )
    }
}
