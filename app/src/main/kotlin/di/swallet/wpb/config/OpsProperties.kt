package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "wpb.ops")
class OpsProperties {
    var trustSnapshotFailureThreshold: Int = 3
    var prometheusEnabled: Boolean = false
}
