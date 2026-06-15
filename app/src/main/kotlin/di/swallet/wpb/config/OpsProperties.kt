/**
 * Configuration properties for operational monitoring and actuator behavior.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Binds `wpb.ops.*` settings for trust snapshot thresholds and Prometheus export. */
@Component
@ConfigurationProperties(prefix = "wpb.ops")
class OpsProperties {
    var trustSnapshotFailureThreshold: Int = 3
    var prometheusEnabled: Boolean = false
}
