package di.swallet.wpb.consent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import di.swallet.wpb.config.ConsentProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.testWalletProperties
import di.swallet.wpb.service.format.DisclosureCipherService
import org.mockito.Mockito.mock

object ConsentTestSupport {

    fun properties(
        enabled: Boolean = true,
        requireExplicit: Boolean = true,
        enforceAllOrNothing: Boolean = true,
        issuanceEnabled: Boolean = true,
    ): ConsentProperties = ConsentProperties().apply {
        this.enabled = enabled
        this.requireExplicitCredentialChoice = requireExplicit
        this.enforceAllOrNothing = enforceAllOrNothing
        this.issuance.enabled = issuanceEnabled
    }

    fun minimizationEvaluator(openId4VpProperties: OpenId4VpProperties = OpenId4VpProperties()): AttributeMinimizationEvaluator =
        AttributeMinimizationEvaluator(openId4VpProperties)

    fun credentialSelector(properties: ConsentProperties = properties()): ConsentCredentialSelector =
        ConsentCredentialSelector(properties, CredentialChoiceGrouper())

    fun presentationConsentViewBuilder(
        properties: ConsentProperties = properties(),
        repository: WalletCredentialRepository = mock(WalletCredentialRepository::class.java),
        openId4VpProperties: OpenId4VpProperties = OpenId4VpProperties(),
    ): PresentationConsentViewBuilder = PresentationConsentViewBuilder(
        properties,
        CredentialChoiceGrouper(),
        minimizationEvaluator(openId4VpProperties),
        repository,
    )

    fun auditRecorder(openId4VpProperties: OpenId4VpProperties = OpenId4VpProperties()): ConsentAuditRecorder =
        ConsentAuditRecorder(CredentialChoiceGrouper(), minimizationEvaluator(openId4VpProperties))

    fun presentationOrchestratorDeps(
        properties: ConsentProperties = properties(),
        repository: WalletCredentialRepository = mock(WalletCredentialRepository::class.java),
        openId4VpProperties: OpenId4VpProperties = OpenId4VpProperties(),
    ): PresentationOrchestratorConsentDeps {
        val minimization = minimizationEvaluator(openId4VpProperties)
        return PresentationOrchestratorConsentDeps(
            consentViewBuilder = presentationConsentViewBuilder(properties, repository, openId4VpProperties),
            consentCredentialSelector = credentialSelector(properties),
            consentSessionGuard = ConsentSessionGuard(),
            consentAuditRecorder = ConsentAuditRecorder(CredentialChoiceGrouper(), minimization),
            minimizationEvaluator = minimization,
        )
    }

    fun pendingCredentialStore(): PendingCredentialStore =
        PendingCredentialStore(
            DisclosureCipherService(testWalletProperties()),
            ObjectMapper().findAndRegisterModules().registerKotlinModule(),
        )

    fun issuanceConsentViewBuilder(
        properties: ConsentProperties = properties(),
        pendingStore: PendingCredentialStore = pendingCredentialStore(),
    ): IssuanceConsentViewBuilder = IssuanceConsentViewBuilder(
        properties,
        pendingStore,
        IssuedCredentialPreviewParser(),
    )

    data class PresentationOrchestratorConsentDeps(
        val consentViewBuilder: PresentationConsentViewBuilder,
        val consentCredentialSelector: ConsentCredentialSelector,
        val consentSessionGuard: ConsentSessionGuard,
        val consentAuditRecorder: ConsentAuditRecorder,
        val minimizationEvaluator: AttributeMinimizationEvaluator,
    )
}
