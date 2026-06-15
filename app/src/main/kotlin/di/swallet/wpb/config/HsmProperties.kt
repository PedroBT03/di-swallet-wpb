/**
 * Configuration properties for PKCS#11 HSM integration.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Configuration mapping for HSM related properties.
 */
@Component
@ConfigurationProperties(prefix = "wpb.hsm")
class HsmProperties {
    var library: String = ""
    var pin: String = ""
    /** Explicit opt-in for the SoftHSM demo PIN (1234). Must stay false outside dev/CI. */
    var allowKnownWeakPin: Boolean = false
}
