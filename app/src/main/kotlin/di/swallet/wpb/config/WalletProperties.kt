package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Typed configuration for the FIDO2/WebAuthn Relying Party.
 */
@Component
@ConfigurationProperties(prefix = "wallet")
data class WalletProperties(
    val rp: RpProperties = RpProperties(),
    val origins: String = "http://localhost,http://localhost:8080,https://localhost",
    val allowUntrustedAttestation: Boolean = true,
    val challenge: ChallengeProperties = ChallengeProperties()
) {
    data class RpProperties(
        val id: String = "localhost",
        val name: String = "DI-Swallet Wallet Provider"
    )

    data class ChallengeProperties(
        val ttlSeconds: Long = 120
    )
}