/**
 * Tests mdoc restart resilience.
 */

package di.swallet.wpb.format.mdoc

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MdocRestartResilienceTest {
    private val binding = MdocTestSupport.holderBinding()

    /**
     * issuerSigned encoded on one stack instance still validates after constructing a fresh stack with the same holder binding.
     */
    @Test
    fun `issuerSigned artifact remains valid across runtime restarts`() {
        val first = MdocTestSupport.stack(holderBindings = listOf(binding))
        val issued = first.codec.encode(
            MdocCredentialDocument(
                docType = "eu.europa.ec.eudi.pid.1",
                namespace = "eu.europa.ec.eudi.pid.1",
                claims = mapOf("given_name" to "Alice"),
            ),
            binding.deviceCoseKey,
        )
        assertTrue(first.codec.validateIssuerSigned(issued))

        val restarted = MdocTestSupport.stack(holderBindings = listOf(binding))
        assertTrue(restarted.codec.validateIssuerSigned(issued))
    }
}
