package di.swallet.wpb.issuance.proof

import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.WalletUnitLifecycleService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Uses WSCD-managed keys for OID4VCI proof material.
 *
 * If a holder has no key yet, a new key is provisioned in the HSM.
 */
@Primary
@Component
class HsmBackedProofMaterialProvider(
    private val hsmService: HsmService,
    private val walletUnitLifecycleService: WalletUnitLifecycleService,
) : ProofMaterialProvider {
    override fun provide(holderId: String, metadata: ResolvedIssuerMetadata?): ProofMaterial {
        val walletUnit = walletUnitLifecycleService.requireIssuanceEligible(holderId)
        val walletKey = runCatching { hsmService.getUserKey(holderId) }
            .getOrElse { hsmService.generateKeyForUser(holderId, walletUnit) }
        val publicKey = decodeEcPublicKey(walletKey.publicKeyBase64)
        return ProofMaterial(
            keyId = walletKey.keyAlias,
            publicKey = publicKey,
            algorithm = "ES256",
        )
    }

    private fun decodeEcPublicKey(publicKeyBase64: String): ECPublicKey {
        val bytes = Base64.getUrlDecoder().decode(publicKeyBase64)
        val spec = X509EncodedKeySpec(bytes)
        val key = KeyFactory.getInstance("EC").generatePublic(spec)
        return key as ECPublicKey
    }
}
