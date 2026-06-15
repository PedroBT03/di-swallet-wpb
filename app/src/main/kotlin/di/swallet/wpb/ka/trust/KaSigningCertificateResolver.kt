/**
 * Resolves the x5c signing certificate chain used in key attestation JWTs.
 */

package di.swallet.wpb.ka.trust

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Component

/** Selects and validates the x5c chain embedded in key attestation JWT headers. */
@Component
class KaSigningCertificateResolver(
    private val properties: OpenId4VciProperties,
    private val hsmService: HsmService,
    private val certificateChainValidator: CertificateChainValidator,
) {
    /**
     * Resolves the KA signing `x5c` chain.
     *
     * - Configured `ka.signing-x5c` takes precedence.
     * - Otherwise falls back to the HSM-stored certificate chain (demo/dev).
     * - When [OpenId4VciProperties.KaProperties.requireConfiguredSigningChain] is true,
     *   a configured chain is mandatory.
     */
    fun resolveSigningChain(walletKey: WalletKey): List<String> {
        val configured = properties.ka.signingX5cChain()
        if (configured.isNotEmpty()) {
            certificateChainValidator.parseDerBase64Chain(configured)
            return configured
        }
        if (properties.ka.requireConfiguredSigningChain) {
            throw IllegalStateException(
                "key attestation requires wpb.openid4vci.ka.signing-x5c when require-configured-signing-chain=true",
            )
        }
        val hsmChain = hsmService.certificateChainBase64(walletKey)
        require(hsmChain.isNotEmpty()) {
            "HSM certificate chain unavailable for wallet key '${walletKey.keyAlias}'"
        }
        certificateChainValidator.parseDerBase64Chain(hsmChain)
        return hsmChain
    }
}
