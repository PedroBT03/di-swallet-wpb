/**
 * Data models for presentation and issuance consent views and assessments.
 */

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

/** Warning shown on the consent screen with a stable machine-readable code. */
data class ConsentWarning(
    val code: String,
    val message: String,
)

/** Result of attribute minimization checks for a presentation session. */
data class MinimizationAssessment(
    val level: MinimizationLevel,
    val warnings: List<ConsentWarning> = emptyList(),
)

/** Verifier identity and trust outcome shown during presentation consent. */
data class VerifierConsentInfo(
    val clientId: String,
    val displayName: String?,
    val trusted: Boolean,
    val trustReason: String?,
)

/** One requested claim path with a display label for the consent UI. */
data class ClaimConsentItem(
    val path: String,
    val label: String,
)

/** One selectable credential within a presentation query group. */
data class CredentialChoiceOption(
    val candidateId: String,
    val credentialId: Long?,
    val label: String,
    val deviceBound: Boolean,
)

/** Credentials matching one presentation query, with a flag when the holder must choose. */
data class CredentialChoiceGroup(
    val queryId: String,
    val credentialType: String,
    val format: CredentialFormat,
    val candidates: List<CredentialChoiceOption>,
    val requiresUserSelection: Boolean,
)

/** One presentation query and the claims the verifier requested. */
data class QueryConsentItem(
    val queryId: String,
    val format: CredentialFormat,
    val credentialTypeHints: List<String>,
    val requestedClaims: List<ClaimConsentItem>,
)

/** Full holder-facing view for an OpenID4VP presentation consent screen. */
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

/** Issuer identity shown during issuance storage consent. */
data class IssuerConsentInfo(
    val credentialIssuerId: String?,
    val displayName: String?,
)

/** One claim name and value previewed before the holder approves storage. */
data class ClaimPreviewItem(
    val name: String,
    val value: String?,
    val previewAvailable: Boolean = true,
)

/** Full holder-facing view for an OpenID4VCI issuance storage consent screen. */
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
