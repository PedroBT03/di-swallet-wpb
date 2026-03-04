package di.swallet.wpb

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WpbApplication

fun main(args: Array<String>) {
    runApplication<WpbApplication>(*args)
}