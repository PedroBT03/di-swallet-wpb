package di.swallet.wpb.presentation.domain

/**
 * Normalized result for RP registry validation (TS5/TS6).
 */
data class RegistryDecision(
    val accepted: Boolean,
    val reason: String? = null,
    val rpIdentifier: String? = null,
    val sourceEndpoint: String? = null,
    val intendedUseChecked: Boolean = false,
)

/**
 * Contact information for the supervisory authority (DPA).
 */
data class SupervisoryAuthorityContact(
    val name: String? = null,
    val country: String? = null,
    val email: List<String> = emptyList(),
    val phone: List<String> = emptyList(),
    val formUri: List<String> = emptyList(),
)

/**
 * Normalized intended use view from TS6 registration data.
 */
data class RegistryIntendedUse(
    val intendedUseIdentifier: String? = null,
    val purpose: List<String> = emptyList(),
    val privacyPolicyUris: List<String> = emptyList(),
    val credentials: List<RegistryCredentialDescriptor> = emptyList(),
)

data class RegistryCredentialDescriptor(
    val format: String? = null,
    val meta: String? = null,
    val claimPaths: List<String> = emptyList(),
)

/**
 * Registry record kept by runtime with normative payload preserved.
 */
data class RpRegistryRecord(
    val identifier: String,
    val tradeName: String? = null,
    val registryUri: String? = null,
    val supportUris: List<String> = emptyList(),
    val entitlements: List<String> = emptyList(),
    val supervisoryAuthority: SupervisoryAuthorityContact? = null,
    val intendedUses: List<RegistryIntendedUse> = emptyList(),
    val rawSignedJwt: String,
    val rawJwtPayloadJson: String,
    val rawDataJson: String,
)
