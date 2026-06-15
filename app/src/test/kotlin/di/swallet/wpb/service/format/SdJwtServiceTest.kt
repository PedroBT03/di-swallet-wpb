/**
 * Tests sd jwt service.
 */

package di.swallet.wpb.service.format

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.format.sdjwt.SdJwtService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.nimbusds.jose.util.Base64URL

class SdJwtServiceTest {

    private val sdJwtService = SdJwtService(ObjectMapper())

    /**
     * createDisclosure output decodes to a Base64URL JSON array containing the claim name and value.
     */
    @Test
    fun `should create valid disclosure format`() {
        val claimName = "given_name"
        val claimValue = "Pedro"
        
        val disclosure = sdJwtService.createDisclosure(claimName, claimValue)
        
        // Decode to verify content: ["salt", "name", "value"]
        val decoded = String(Base64URL(disclosure).decode())
        
        assertTrue(decoded.startsWith("[\""))
        assertTrue(decoded.contains(claimName))
        assertTrue(decoded.contains(claimValue))
    }

    /**
     * Two disclosures for the same claim name and value differ because salts are random.
     */
    @Test
    fun `should produce unique disclosures for same claim due to random salts`() {
        val disclosure1 = sdJwtService.createDisclosure("age", 25)
        val disclosure2 = sdJwtService.createDisclosure("age", 25)
        
        assertNotEquals(disclosure1, disclosure2)
    }

    /**
     * createNestedObjectDisclosures for address/locality yields two disclosures and digests; parent disclosure references _sd.
     */
    @Test
    fun `nested object issuance includes parent and child disclosures`() {
        val issued = sdJwtService.createNestedObjectDisclosures(
            "address",
            mapOf("locality" to "Lisbon"),
        )
        assertEquals(2, issued.disclosures.size)
        assertEquals(2, issued.digests.size)
        val decoded = String(Base64URL(issued.disclosures.last()).decode())
        assertTrue(decoded.contains("_sd"))
        assertTrue(decoded.contains("address"))
    }

    /**
     * disclosuresFromClaimMap on scalar plus nested map produces three disclosures and matching digests.
     */
    @Test
    fun `disclosuresFromClaimMap nests maps and flattens scalars`() {
        val issued = sdJwtService.disclosuresFromClaimMap(
            mapOf(
                "given_name" to "Pedro",
                "address" to mapOf("locality" to "Lisbon"),
            ),
        )
        assertEquals(3, issued.disclosures.size)
        assertEquals(3, issued.digests.size)
    }
}
