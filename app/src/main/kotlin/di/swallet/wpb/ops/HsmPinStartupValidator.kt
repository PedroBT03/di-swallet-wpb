package di.swallet.wpb.ops

import di.swallet.wpb.config.HsmProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Rejects the known SoftHSM demo PIN unless explicitly opted in (local dev / CI only).
 */
@Component
@Order(0)
class HsmPinStartupValidator(
    private val hsmProperties: HsmProperties,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (!isWeakPin(hsmProperties.pin)) return

        if (hsmProperties.allowKnownWeakPin) {
            logger.warn(
                "HSM PIN uses the known SoftHSM demo default. " +
                    "Set HSM_PIN and wpb.hsm.allow-known-weak-pin=false before staging or production.",
            )
            return
        }

        throw IllegalStateException(
            "wpb.hsm.pin is blank or uses the known weak default (${WeakSecretDefaults.KNOWN_WEAK_HSM_PIN}). " +
                "Set HSM_PIN to a non-default secret, or set wpb.hsm.allow-known-weak-pin=true for local/CI only.",
        )
    }

    private fun isWeakPin(pin: String): Boolean =
        pin.isBlank() || pin == WeakSecretDefaults.KNOWN_WEAK_HSM_PIN
}
