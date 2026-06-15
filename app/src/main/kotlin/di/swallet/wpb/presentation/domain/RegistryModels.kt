/**
 * Domain models for TS5/TS6 relying-party registry validation results.
 */

package di.swallet.wpb.presentation.domain

/**
 * Outcome of validating a verifier against the RP registry.
 */
data class RegistryDecision(
    val accepted: Boolean,
    val reason: String? = null,
    val rpIdentifier: String? = null,
    val sourceEndpoint: String? = null,
    val intendedUseChecked: Boolean = false,
)

/**
 * Supervisory authority contact details from a registry record.
 */
data class SupervisoryAuthorityContact(
    val name: String? = null,
    val country: String? = null,
    val email: List<String> = emptyList(),
    val phone: List<String> = emptyList(),
    val formUri: List<String> = emptyList(),
)

/**
 * Registered intended use extracted from TS6 registration data.
 */
data class RegistryIntendedUse(
    val intendedUseIdentifier: String? = null,
    val purpose: List<String> = emptyList(),
    val privacyPolicyUris: List<String> = emptyList(),
    val credentials: List<RegistryCredentialDescriptor> = emptyList(),
)

/**
 * Credential format and claim paths allowed under one intended use entry.
 */
data class RegistryCredentialDescriptor(
    val format: String? = null,
    val meta: String? = null,
    val claimPaths: List<String> = emptyList(),
)

/**
 * Normalized RP registry record with raw signed payload preserved for audit.
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
