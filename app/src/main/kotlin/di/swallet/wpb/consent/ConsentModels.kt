package di.swallet.wpb.consent

import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.domain.IssuanceState
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationState
import java.util.UUID

enum class MinimizationLevel {
    OK,
    WARNING,
}

enum class ApprovalMode {
    ALL_OR_NOTHING,
}

data class ConsentWarning(
    val code: String,
    val message: String,
)

data class MinimizationAssessment(
    val level: MinimizationLevel,
    val warnings: List<ConsentWarning> = emptyList(),
)

data class VerifierConsentInfo(
    val clientId: String,
    val displayName: String?,
    val trusted: Boolean,
    val trustReason: String?,
)

data class ClaimConsentItem(
    val path: String,
    val label: String,
)

data class CredentialChoiceOption(
    val candidateId: String,
    val credentialId: Long?,
    val label: String,
    val deviceBound: Boolean,
)

data class CredentialChoiceGroup(
    val queryId: String,
    val credentialType: String,
    val format: CredentialFormat,
    val candidates: List<CredentialChoiceOption>,
    val requiresUserSelection: Boolean,
)

data class QueryConsentItem(
    val queryId: String,
    val format: CredentialFormat,
    val credentialTypeHints: List<String>,
    val requestedClaims: List<ClaimConsentItem>,
)

data class PresentationConsentView(
    val sessionId: UUID,
    val state: PresentationState,
    val holderId: String?,
    val verifier: VerifierConsentInfo,
    val intendedUse: List<String>,
    val privacyPolicyUri: String?,
    val registryWarnings: List<ConsentWarning>,
    val minimization: MinimizationAssessment,
    val queries: List<QueryConsentItem>,
    val choiceGroups: List<CredentialChoiceGroup>,
    val approvalMode: ApprovalMode,
)

data class IssuerConsentInfo(
    val credentialIssuerId: String?,
    val displayName: String?,
)

data class ClaimPreviewItem(
    val name: String,
    val value: String?,
    val previewAvailable: Boolean = true,
)

data class IssuanceConsentView(
    val sessionId: UUID,
    val state: IssuanceState,
    val holderId: String?,
    val issuer: IssuerConsentInfo,
    val credentialConfigurationId: String?,
    val format: IssuanceCredentialFormat,
    val deviceBound: Boolean,
    val claimPreview: List<ClaimPreviewItem>,
)
