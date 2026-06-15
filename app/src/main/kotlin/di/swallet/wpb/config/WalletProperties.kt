/**
 * Configuration properties for FIDO2 wallet authentication and disclosure encryption.
 */

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
    val challenge: ChallengeProperties = ChallengeProperties(),
    val disclosures: DisclosuresProperties = DisclosuresProperties()
) {
    /** WebAuthn relying party id and display name. */
    data class RpProperties(
        val id: String = "localhost",
        val name: String = "DI-Swallet Wallet Provider"
    )

    /** TTL for stored FIDO2 assertion challenges. */
    data class ChallengeProperties(
        val ttlSeconds: Long = 120
    )

    /** AES key used to encrypt SD-JWT disclosures at rest. */
    data class DisclosuresProperties(
        var encryptionKey: String = "",
    )
}
