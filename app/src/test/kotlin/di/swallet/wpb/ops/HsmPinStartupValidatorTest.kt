/**
 * Tests hsm pin startup validator.
 */

package di.swallet.wpb.ops

import di.swallet.wpb.config.HsmProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

class HsmPinStartupValidatorTest {

    /**
     * Configures the known weak default HSM pin with allowKnownWeakPin=false and expects
     * startup validation to throw IllegalStateException.
     */
    @Test
    fun `fails when weak pin is used without explicit opt-in`() {
        val validator = HsmPinStartupValidator(
            hsmProperties = HsmProperties().apply {
                pin = WeakSecretDefaults.KNOWN_WEAK_HSM_PIN
                allowKnownWeakPin = false
            },
        )
        assertThrows(IllegalStateException::class.java) { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Uses the known weak HSM pin but sets allowKnownWeakPin=true and expects startup
     * validation to complete without throwing.
     */
    @Test
    fun `allows weak pin when explicitly opted in`() {
        val validator = HsmPinStartupValidator(
            hsmProperties = HsmProperties().apply {
                pin = WeakSecretDefaults.KNOWN_WEAK_HSM_PIN
                allowKnownWeakPin = true
            },
        )
        assertDoesNotThrow { validator.run(DefaultApplicationArguments()) }
    }

    /**
     * Sets a non-default staging HSM pin and expects startup validation to pass without
     * requiring the weak-pin opt-in flag.
     */
    @Test
    fun `passes with non-default pin`() {
        val validator = HsmPinStartupValidator(
            hsmProperties = HsmProperties().apply {
                pin = "staging-hsm-pin"
            },
        )
        assertDoesNotThrow { validator.run(DefaultApplicationArguments()) }
    }
}
