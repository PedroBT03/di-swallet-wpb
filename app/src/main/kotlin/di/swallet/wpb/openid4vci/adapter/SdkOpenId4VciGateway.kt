package di.swallet.wpb.openid4vci.adapter

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.DeferredIssuanceHandle
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.openid4vci.protocol.AuthorizedContext
import di.swallet.wpb.openid4vci.protocol.DeferredQueryOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceOutcome
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.openid4vci.protocol.NotificationEvent
import di.swallet.wpb.openid4vci.protocol.PreparedAuthorization
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.openid4vci.protocol.ResolvedOffer
import di.swallet.wpb.openid4vci.protocol.WalletAttestationTransport
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Real SDK-backed OID4VCI adapter.
 *
 * Scaffolded for Phase 2 MVP. This is the only place in the codebase
 * that is allowed to import `eu.europa.ec.eudi.openid4vci.*`. The
 * intent is that all SDK lifecycle (`Issuer.make`, `prepareAuthorizationRequest`,
 * `authorizeWithAuthorizationCode`, `request`, `queryForDeferredCredential`,
 * `notify`) is wired here and exposes domain DTOs to the orchestrator.
 *
 * The Phase 2 increment ships this adapter as a structural placeholder.
 * The simulator gateway ([SimulatedOpenId4VciGateway]) is the only
 * adapter activated by default and the only one covered by tests. Real
 * SDK wiring lands in a follow-up increment once an integration target
 * issuer is available; this class throws [UnsupportedOperationException]
 * for every operation so any deployment that flips `demo-mode=false`
 * without finishing the wiring fails loudly.
 */
@Component("sdkOpenId4VciGateway")
@ConditionalOnProperty(prefix = "wpb.openid4vci", name = ["demo-mode"], havingValue = "false")
class SdkOpenId4VciGateway(
    private val properties: OpenId4VciProperties,
) : OpenId4VciGateway {

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        logger.warn(
            "SdkOpenId4VciGateway is a scaffolded placeholder. Real SDK wiring will land " +
                "in a follow-up increment (Phase 2/3 hand-off). Configured issuer hint: '{}'",
            properties.sdk.credentialIssuerId,
        )
    }

    override fun resolveOffer(offerUri: String) = notImplemented("resolveOffer")
    override fun resolveMetadata(
        credentialIssuerId: String,
        credentialConfigurationIds: List<String>,
    ): ResolvedIssuerMetadata = notImplemented("resolveMetadata")

    override fun prepareAuthorization(
        adapterSessionId: String,
        offer: ResolvedOffer,
        metadata: ResolvedIssuerMetadata,
        proof: ProofMaterial,
        walletAttestation: WalletAttestationTransport,
    ): PreparedAuthorization = notImplemented("prepareAuthorization")

    override fun authorizeWithCode(
        adapterSessionId: String,
        authorizationCode: String,
        state: String,
        walletAttestation: WalletAttestationTransport,
    ): AuthorizedContext = notImplemented("authorizeWithCode")

    override fun authorizeWithPreAuthorizedCode(
        adapterSessionId: String,
        offer: ResolvedOffer,
        metadata: ResolvedIssuerMetadata,
        proof: ProofMaterial,
        txCode: String?,
        walletAttestation: WalletAttestationTransport,
    ): AuthorizedContext = notImplemented("authorizeWithPreAuthorizedCode")

    override fun requestCredential(
        adapterSessionId: String,
        request: IssuanceRequest,
        proof: ProofMaterial,
    ): IssuanceOutcome = notImplemented("requestCredential")

    override fun queryDeferred(
        adapterSessionId: String,
        handle: DeferredIssuanceHandle,
    ): DeferredQueryOutcome = notImplemented("queryDeferred")

    override fun notify(
        adapterSessionId: String,
        notificationId: String,
        event: NotificationEvent,
        description: String?,
    ): Boolean = notImplemented("notify")

    override fun discard(adapterSessionId: String) {
        logger.debug("discard not yet wired in SdkOpenId4VciGateway")
    }

    private fun notImplemented(op: String): Nothing =
        throw UnsupportedOperationException(
            "SdkOpenId4VciGateway.$op is a Phase 2 scaffold. " +
                "Configure wpb.openid4vci.demo-mode=true or implement the SDK call.",
        )
}
