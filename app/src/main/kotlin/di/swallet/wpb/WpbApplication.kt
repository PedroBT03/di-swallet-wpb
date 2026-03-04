package di.swallet.wpb

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties

@SpringBootApplication
@EnableConfigurationProperties(HsmProperties::class)
class WpbApplication

fun main(args: Array<String>) {
    runApplication<WpbApplication>(*args)
}