/**
 * Tests ephemeral proof material provider.
 */

package di.swallet.wpb.issuance.proof

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EphemeralProofMaterialProviderTest {

    /**
     * Two provide calls for the same holder return the same ProofMaterial instance with algorithm ES256.
     */
    @Test
    fun `same holder receives stable proof material`() {
        val provider = EphemeralProofMaterialProvider()
        val a = provider.provide("holder-1", null)
        val b = provider.provide("holder-1", null)
        assertSame(a, b)
        assertEquals("ES256", a.algorithm)
    }

    /**
     * Different holder ids receive distinct proof key ids.
     */
    @Test
    fun `different holders receive different keys`() {
        val provider = EphemeralProofMaterialProvider()
        val a = provider.provide("holder-1", null)
        val b = provider.provide("holder-2", null)
        assert(a.keyId != b.keyId)
    }
}
