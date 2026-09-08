/**
 * Actuator info contributor exposing non-secret operational configuration details.
 */

package di.swallet.wpb.ops

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.SwaggerProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletProperties
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.util.Optional

/**
 * Publishes active profile, demo flags, and build metadata on the actuator /info endpoint.
 */
@Component
class WpbInfoContributor(
    private val environment: Environment,
    private val openId4VpProperties: OpenId4VpProperties,
    private val openId4VciProperties: OpenId4VciProperties,
    private val walletProperties: WalletProperties,
    private val transactionLogProperties: TransactionLogProperties,
    private val swaggerProperties: SwaggerProperties,
    private val buildProperties: Optional<BuildProperties>,
) : InfoContributor {
    /**
     * Adds operational configuration and build details to the actuator info response.
     */
    override fun contribute(builder: Info.Builder) {
        val operational = linkedMapOf<String, Any>(
            "profile" to environment.activeProfiles.toList(),
            "demoMode" to mapOf(
                "openid4vp" to openId4VpProperties.demoMode,
                "openid4vci" to openId4VciProperties.demoMode,
            ),
            "untrustedAttestation" to walletProperties.allowUntrustedAttestation,
            "swaggerEnabled" to swaggerProperties.enabled,
            "registryEnabled" to openId4VpProperties.registry.enabled,
            "trustSourceMode" to openId4VpProperties.trust.sourceModeNormalized(),
            "transactionLog" to mapOf(
                "dekMode" to transactionLogProperties.resolvedDekMode().name.lowercase(),
            ),
        )
        buildProperties.ifPresent { build ->
            operational["version"] = build.version ?: "unknown"
            operational["gitCommit"] = build.get("git.commit.id.abbrev") ?: build.get("commit.id.abbrev") ?: "unknown"
        }
        builder.withDetail("operational", operational)
    }
}
