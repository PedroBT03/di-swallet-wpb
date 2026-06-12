package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "wpb.swagger")
class SwaggerProperties {
    var enabled: Boolean = true
}
