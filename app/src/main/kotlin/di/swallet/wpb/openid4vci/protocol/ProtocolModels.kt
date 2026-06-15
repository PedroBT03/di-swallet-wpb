/**
 * OpenID4VCI protocol models shared by adapters, controllers, and orchestration.
 */

package di.swallet.wpb.openid4vci.protocol

import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import java.time.Instant

/** Authorization grant type advertised by a credential offer. */
enum class AuthorizationFlowKind {
    AUTHORIZATION_CODE,
    PRE_AUTHORIZED_CODE,
}

/** Pre-authorized code grant details extracted from a credential offer. */
data class PreAuthorizedGrant(
    val txCodeRequired: Boolean,
    val txCodeDescription: String? = null,
    val txCodeLength: Int? = null,
)

/** Credential configuration advertised by an issuer in its metadata. */
data class CredentialConfigurationDescriptor(
    val id: String,
    val format: IssuanceCredentialFormat,
    val docType: String? = null,
    val vct: String? = null,
    val cryptographicBindingMethodsSupported: List<String> = emptyList(),
    val proofTypesSupported: List<String> = emptyList(),
    val keyAttestationRequired: Boolean = false,
    val preferredKeyStorageStatusPeriodDays: Int? = null,
    val display: List<Map<String, String>> = emptyList(),
)

/** OAuth authorization server metadata captured by the adapter. */
data class AuthorizationServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val pushedAuthorizationRequestEndpoint: String? = null,
    val supportsPar: Boolean = false,
    val supportsDpop: Boolean = false,
    val grantTypesSupported: List<String> = emptyList(),
)

/** Issuer metadata normalized for orchestration after adapter resolution. */
data class ResolvedIssuerMetadata(
    val credentialIssuerId: String,
    val credentialEndpoint: String? = null,
    val deferredCredentialEndpoint: String? = null,
    val notificationEndpoint: String? = null,
    val signedMetadataPresent: Boolean = false,
    /** Compact JWS from the issuer metadata `signed_metadata` field, when present. */
    val signedMetadataJwt: String? = null,
    val credentialConfigurations: List<CredentialConfigurationDescriptor> = emptyList(),
    val authorizationServers: List<AuthorizationServerMetadata> = emptyList(),
)

/** Parsed credential offer resolved by value or by reference. */
data class ResolvedOffer(
    val credentialIssuerId: String,
    val credentialConfigurationIds: List<String>,
    val authorizationFlow: AuthorizationFlowKind,
    val preAuthorizedGrant: PreAuthorizedGrant? = null,
    val authorizationServer: String? = null,
    val preAuthorizedCode: String? = null,
)

/**
 * Authorization redirect details produced before the holder completes OAuth.
 * [adapterSessionId] references in-flight SDK state inside the adapter.
 */
data class PreparedAuthorization(
    val adapterSessionId: String,
    val authorizationCodeUrl: String,
    val state: String,
    val pkceUsed: Boolean = true,
    val parUsed: Boolean = false,
    val dpopRequested: Boolean = false,
    val wiaAttached: Boolean = false,
)

/** Token exchange outcome returned to orchestration after authorization completes. */
data class AuthorizedContext(
    val adapterSessionId: String,
    val accessTokenPresent: Boolean,
    val refreshTokenPresent: Boolean,
    val dpopUsed: Boolean = false,
    val cNoncePresent: Boolean = false,
    val authorizationServer: String? = null,
    val accessTokenCnfJkt: String? = null,
    val wiaCnfJkt: String? = null,
)

/**
 * Wallet attestation and proof-of-possession tokens sent to the issuer.
 * These are attached to PAR and token requests by the adapter.
 */
data class WalletAttestationTransport(
    val jwt: String,
    val popJwt: String,
    val cnfJkt: String,
    val expiresAt: Instant,
)

/** Device key attestation envelope required for device-bound credential issuance. */
data class KeyAttestationTransport(
    val jwt: String,
    val keyId: String,
    val attestedJkt: String,
    val keyStorage: String,
    val certification: String,
    val expiresAt: Instant,
    val statusListUri: String,
    val statusListIndex: Int,
)

/**
 * Single credential issuance request sent to the issuer.
 * Either [credentialConfigurationId] or [credentialIdentifier] must be set.
 */
data class IssuanceRequest(
    val credentialConfigurationId: String? = null,
    val credentialIdentifier: String? = null,
    val claims: List<String> = emptyList(),
)

/** Credential payload returned by the issuer to the wallet. */
data class IssuedCredential(
    val credentialConfigurationId: String,
    val format: IssuanceCredentialFormat,
    val rawPayload: String,
    val notificationId: String? = null,
    val receivedAt: Instant = Instant.now(),
)

/** Outcome of a credential request to the issuer. */
sealed interface IssuanceOutcome {
    /** Credential issued synchronously by the issuer. */
    data class Issued(val credentials: List<IssuedCredential>) : IssuanceOutcome

    /** Issuance deferred; poll with the returned handle. */
    data class Deferred(val handle: di.swallet.wpb.issuance.domain.DeferredIssuanceHandle) : IssuanceOutcome

    /** Credential request failed before a credential could be returned. */
    data class Failed(val code: String, val message: String) : IssuanceOutcome
}

/** Outcome of polling a deferred issuance endpoint. */
sealed interface DeferredQueryOutcome {
    /** Deferred issuance completed and credentials are available. */
    data class Issued(val credentials: List<IssuedCredential>) : DeferredQueryOutcome

    /** Issuance is still pending; continue polling with the updated handle. */
    data class StillPending(val updatedHandle: di.swallet.wpb.issuance.domain.DeferredIssuanceHandle) : DeferredQueryOutcome

    /** Deferred polling failed. */
    data class Failed(val code: String, val message: String) : DeferredQueryOutcome
}

/** Wallet-to-issuer notification events defined by OID4VCI. */
enum class NotificationEvent {
    CREDENTIAL_ACCEPTED,
    CREDENTIAL_DELETED,
    CREDENTIAL_FAILURE,
}
