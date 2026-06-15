/**
 * Adapter port for all EUDI OID4VCI SDK interactions.
 */

package di.swallet.wpb.openid4vci.adapter

import di.swallet.wpb.issuance.domain.DeferredIssuanceHandle
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.openid4vci.protocol.AuthorizedContext
import di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.openid4vci.protocol.PreparedAuthorization
import di.swallet.wpb.openid4vci.protocol.KeyAttestationTransport
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.openid4vci.protocol.ResolvedOffer
import di.swallet.wpb.openid4vci.protocol.WalletAttestationTransport

/**
 * Adapter port that encapsulates all interactions with the EUDI OID4VCI SDK.
 *
 * Implementations live in `di.swallet.wpb.openid4vci.adapter` and are the
 * only place allowed to import `eu.europa.ec.eudi.openid4vci.*`. Orchestrator
 * and controllers only see the domain DTOs declared in
 * `di.swallet.wpb.openid4vci.protocol`.
 */
interface OpenId4VciGateway {

    /**
     * Resolve a credential offer by value (URL with `credential_offer=` parameter)
     * or by reference (URL with `credential_offer_uri=` parameter).
     */
    fun resolveOffer(offerUri: String): Pair<ResolvedOffer, ResolvedIssuerMetadata>

    /**
     * Resolve issuer + authorization-server metadata for a wallet-initiated flow
     * where the wallet already knows the credential issuer identifier and the
     * desired credential configuration identifiers.
     */
    fun resolveMetadata(
        credentialIssuerId: String,
        credentialConfigurationIds: List<String>,
    ): ResolvedIssuerMetadata

    /**
     * Prepare an authorization-code grant (PKCE; PAR when supported by the issuer).
     *
     * The returned [PreparedAuthorization] carries an opaque
     * `adapterSessionId` used to recover the in-flight SDK state on the
     * subsequent `authorizeWithCode` call.
     */
    fun prepareAuthorization(
        adapterSessionId: String,
        offer: ResolvedOffer,
        metadata: ResolvedIssuerMetadata,
        proof: ProofMaterial,
        walletAttestation: WalletAttestationTransport,
    ): PreparedAuthorization

    /**
     * Complete the authorization code flow with the authorization code and state
     * returned by the issuer/authorization server.
     */
    fun authorizeWithCode(
        adapterSessionId: String,
        authorizationCode: String,
        state: String,
        walletAttestation: WalletAttestationTransport,
    ): AuthorizedContext

    /**
     * Complete the pre-authorized code flow. `txCode` is required if the
     * issuer signalled `tx_code_required = true` in the offer.
     */
    fun authorizeWithPreAuthorizedCode(
        adapterSessionId: String,
        offer: ResolvedOffer,
        metadata: ResolvedIssuerMetadata,
        proof: ProofMaterial,
        txCode: String?,
        walletAttestation: WalletAttestationTransport,
    ): AuthorizedContext

    /**
     * Request a credential. The adapter takes care of c_nonce, DPoP nonce retry
     * and proof construction using the supplied [ProofMaterial].
     */
    fun requestCredential(
        adapterSessionId: String,
        request: IssuanceRequest,
        proof: ProofMaterial,
        keyAttestation: KeyAttestationTransport?,
    ): IssuanceOutcome

    /**
     * Poll a previously deferred issuance. The handle may be refreshed with
     * a new transaction identifier on each poll (per RFC).
     */
    fun queryDeferred(
        adapterSessionId: String,
        handle: DeferredIssuanceHandle,
    ): DeferredQueryOutcome

    /**
     * Send a wallet -> issuer notification.
     */
    fun notify(
        adapterSessionId: String,
        notificationId: String,
        event: NotificationEvent,
        description: String? = null,
    ): Boolean

    /**
     * Forget all SDK state attached to the given adapter session id.
     */
    fun discard(adapterSessionId: String)
}
