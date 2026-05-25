package di.swallet.wpb.openid4vci.protocol

import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import java.time.Instant

/**
 * Authorization flow kind exposed by an OID4VCI credential offer.
 */
enum class AuthorizationFlowKind {
    AUTHORIZATION_CODE,
    PRE_AUTHORIZED_CODE,
}

/**
 * Pre-authorized code grant details extracted from a credential offer.
 */
data class PreAuthorizedGrant(
    val txCodeRequired: Boolean,
    val txCodeDescription: String? = null,
    val txCodeLength: Int? = null,
)

/**
 * Lightweight description of a credential configuration advertised by an issuer.
 */
data class CredentialConfigurationDescriptor(
    val id: String,
    val format: IssuanceCredentialFormat,
    val docType: String? = null,
    val vct: String? = null,
    val display: List<Map<String, String>> = emptyList(),
)

/**
 * Authorization server metadata captured by the adapter.
 */
data class AuthorizationServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val pushedAuthorizationRequestEndpoint: String? = null,
    val supportsPar: Boolean = false,
    val supportsDpop: Boolean = false,
    val grantTypesSupported: List<String> = emptyList(),
)

/**
 * Issuer metadata captured by the adapter and bound to the orchestrator.
 */
data class ResolvedIssuerMetadata(
    val credentialIssuerId: String,
    val credentialEndpoint: String? = null,
    val deferredCredentialEndpoint: String? = null,
    val notificationEndpoint: String? = null,
    val signedMetadataPresent: Boolean = false,
    val credentialConfigurations: List<CredentialConfigurationDescriptor> = emptyList(),
    val authorizationServers: List<AuthorizationServerMetadata> = emptyList(),
)

/**
 * Result of resolving a credential offer (by-value or by-reference).
 */
data class ResolvedOffer(
    val credentialIssuerId: String,
    val credentialConfigurationIds: List<String>,
    val authorizationFlow: AuthorizationFlowKind,
    val preAuthorizedGrant: PreAuthorizedGrant? = null,
    val authorizationServer: String? = null,
)

/**
 * Adapter-owned authorization context that the orchestrator passes back when
 * completing the authorization code grant.
 *
 * `adapterSessionId` references the per-session SDK state stored inside the
 * adapter (Issuer + Prepared/Authorized request), keeping SDK types out of the
 * orchestrator.
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

/**
 * Adapter-opaque authorization context returned after token exchange.
 */
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
 * WIA transport envelope passed from orchestration to the adapter.
 *
 * It represents the generated wallet attestation and its PoP token that must
 * be attached to PAR/token requests.
 */
data class WalletAttestationTransport(
    val jwt: String,
    val popJwt: String,
    val cnfJkt: String,
    val expiresAt: Instant,
)

/**
 * Request payload for issuing a single credential.
 *
 * Either `credentialConfigurationId` or `credentialIdentifier` must be set.
 */
data class IssuanceRequest(
    val credentialConfigurationId: String? = null,
    val credentialIdentifier: String? = null,
    val claims: List<String> = emptyList(),
)

/**
 * Issued credential payload returned by the issuer to the wallet.
 */
data class IssuedCredential(
    val credentialConfigurationId: String,
    val format: IssuanceCredentialFormat,
    val rawPayload: String,
    val notificationId: String? = null,
    val receivedAt: Instant = Instant.now(),
)

/**
 * Outcome of a credential request to the issuer.
 */
sealed interface IssuanceOutcome {
    data class Issued(val credentials: List<IssuedCredential>) : IssuanceOutcome
    data class Deferred(val handle: di.swallet.wpb.issuance.domain.DeferredIssuanceHandle) : IssuanceOutcome
    data class Failed(val code: String, val message: String) : IssuanceOutcome
}

/**
 * Outcome of polling a deferred issuance endpoint.
 */
sealed interface DeferredQueryOutcome {
    data class Issued(val credentials: List<IssuedCredential>) : DeferredQueryOutcome
    data class StillPending(val updatedHandle: di.swallet.wpb.issuance.domain.DeferredIssuanceHandle) : DeferredQueryOutcome
    data class Failed(val code: String, val message: String) : DeferredQueryOutcome
}

/**
 * Wallet -> Issuer notification event payload.
 */
enum class NotificationEvent {
    CREDENTIAL_ACCEPTED,
    CREDENTIAL_DELETED,
    CREDENTIAL_FAILURE,
}
