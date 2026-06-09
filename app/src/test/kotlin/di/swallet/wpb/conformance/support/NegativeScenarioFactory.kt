package di.swallet.wpb.conformance.support

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.registry.RegistryValidator
import di.swallet.wpb.presentation.trust.AccessCertificateValidationResult
import di.swallet.wpb.presentation.trust.AccessCertificateValidationService
import di.swallet.wpb.presentation.trust.DefaultTrustValidator
import di.swallet.wpb.presentation.trust.DefaultVerifierCertificateExtractor
import di.swallet.wpb.presentation.trust.PkixAccessCertificateValidationService
import di.swallet.wpb.presentation.trust.VerifierCertificateExtractor
import di.swallet.wpb.presentation.trust.VerifierCertificateMaterial
import di.swallet.wpb.trust.core.TrustBindingRule
import di.swallet.wpb.trust.core.TrustSnapshot
import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import di.swallet.wpb.trust.core.TrustSnapshotResolver
import di.swallet.wpb.trust.core.TrustedEntity
import org.mockito.Mockito.mock
import java.security.cert.X509Certificate
import java.time.Instant

enum class TrustScenario {
    TRUSTED,
    UNTRUSTED_CLIENT,
}

enum class RegistryScenario {
    ACCEPTED,
    REJECTED,
}

object NegativeScenarioFactory {

    fun trustProperties(
        allowedClientId: String = "verifier-demo-client",
        demoMode: Boolean = false,
    ): OpenId4VpProperties = OpenId4VpProperties().apply {
        this.demoMode = demoMode
        trust.allowedClientIds = allowedClientId
        trust.allowFailOpenInDemoMode = false
    }

    fun trustValidator(
        properties: OpenId4VpProperties,
        scenario: TrustScenario,
        allowedClientId: String = "verifier-demo-client",
    ): DefaultTrustValidator {
        val effectiveProperties = when (scenario) {
            TrustScenario.TRUSTED -> properties
            TrustScenario.UNTRUSTED_CLIENT -> properties.apply {
                trust.allowedClientIds = "another-verifier"
            }
        }
        val snapshot = TrustSnapshot(
            trustAnchors = emptyList(),
            entities = mapOf(
                allowedClientId to TrustedEntity(
                    entityId = allowedClientId,
                    bindings = setOf(TrustBindingRule("client_id", allowedClientId)),
                ),
            ),
            source = "conformance-test",
            loadedAt = Instant.now(),
        )
        val resolver = object : TrustSnapshotResolver {
            override fun currentAvailability(): TrustSnapshotAvailability =
                TrustSnapshotAvailability.Available(snapshot)
        }
        val extractor: VerifierCertificateExtractor = object : VerifierCertificateExtractor {
            override fun extract(request: di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest): VerifierCertificateMaterial? {
                val cert = mock(X509Certificate::class.java)
                return VerifierCertificateMaterial(chain = listOf(cert), leaf = cert)
            }
        }
        val certValidator: AccessCertificateValidationService = object : AccessCertificateValidationService {
            override fun validate(
                requestClientId: String,
                material: VerifierCertificateMaterial,
                snapshot: TrustSnapshot,
            ): AccessCertificateValidationResult = AccessCertificateValidationResult.Trusted
        }
        return DefaultTrustValidator(
            properties = effectiveProperties,
            trustSnapshotResolver = resolver,
            certificateExtractor = extractor,
            certificateValidationService = certValidator,
        )
    }

    fun registryValidator(scenario: RegistryScenario): RegistryValidator = object : RegistryValidator {
        override fun validate(context: PresentationContext): PresentationContext {
            val accepted = scenario == RegistryScenario.ACCEPTED
            return context.copy(
                registryDecision = di.swallet.wpb.presentation.domain.RegistryDecision(
                    accepted = accepted,
                    reason = if (accepted) "ok" else "registry denied",
                    rpIdentifier = context.authorizationRequest?.clientId,
                    sourceEndpoint = "/wrp/{identifier}",
                    intendedUseChecked = true,
                ),
            )
        }
    }
}
