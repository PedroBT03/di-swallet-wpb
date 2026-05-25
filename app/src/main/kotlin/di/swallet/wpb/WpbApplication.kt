package di.swallet.wpb

import di.swallet.wpb.config.HsmProperties
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(
    HsmProperties::class,
    OpenId4VpProperties::class,
    OpenId4VciProperties::class,
)
class WpbApplication

fun main(args: Array<String>) {
    runApplication<WpbApplication>(*args)
}