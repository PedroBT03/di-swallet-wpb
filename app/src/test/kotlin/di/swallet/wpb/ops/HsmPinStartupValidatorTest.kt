package di.swallet.wpb.ops

import di.swallet.wpb.config.HsmProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HsmPinStartupValidatorTest {

    @Test
    fun `fails when weak pin is used without explicit opt-in`() {
        val validator = HsmPinStartupValidator(
            hsmProperties = HsmProperties().apply {
                pin = WeakSecretDefaults.KNOWN_WEAK_HSM_PIN
                allowKnownWeakPin = false
            },
        )
        assertThrows(IllegalStateException::class.java) { validator.run(null) }
    }

    @Test
    fun `allows weak pin when explicitly opted in`() {
        val validator = HsmPinStartupValidator(
            hsmProperties = HsmProperties().apply {
                pin = WeakSecretDefaults.KNOWN_WEAK_HSM_PIN
                allowKnownWeakPin = true
            },
        )
        assertDoesNotThrow { validator.run(null) }
    }

    @Test
    fun `passes with non-default pin`() {
        val validator = HsmPinStartupValidator(
            hsmProperties = HsmProperties().apply {
                pin = "staging-hsm-pin"
            },
        )
        assertDoesNotThrow { validator.run(null) }
    }
}
