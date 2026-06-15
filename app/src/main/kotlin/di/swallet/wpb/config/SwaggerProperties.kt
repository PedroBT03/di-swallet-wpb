/**
 * Configuration properties for OpenAPI/Swagger documentation exposure.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Binds `wpb.swagger.*` settings that control API documentation availability. */
@Component
@ConfigurationProperties(prefix = "wpb.swagger")
class SwaggerProperties {
    var enabled: Boolean = true
}
