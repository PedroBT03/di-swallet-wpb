package di.swallet.wpb

import di.swallet.wpb.config.DataDeletionRequestProperties
import di.swallet.wpb.config.DpaReportProperties
import di.swallet.wpb.config.PseudonymProperties
import di.swallet.wpb.config.TrustMarkProperties
import di.swallet.wpb.config.HsmProperties
import di.swallet.wpb.config.MdocProperties
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.config.TransactionLogProperties
import di.swallet.wpb.config.WalletBindingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(
    HsmProperties::class,
    MdocProperties::class,
    OpenId4VpProperties::class,
    OpenId4VciProperties::class,
    WalletBindingProperties::class,
    StatusListProperties::class,
    TransactionLogProperties::class,
    DataDeletionRequestProperties::class,
    DpaReportProperties::class,
    TrustMarkProperties::class,
    PseudonymProperties::class,
)
class WpbApplication

fun main(args: Array<String>) {
    runApplication<WpbApplication>(*args)
}