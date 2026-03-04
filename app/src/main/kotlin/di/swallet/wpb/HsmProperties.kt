package di.swallet.wpb

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Configuration mapping for HSM related properties.
 */
@Component
@ConfigurationProperties(prefix = "wpb.hsm")
class HsmProperties {
    var library: String = ""
}