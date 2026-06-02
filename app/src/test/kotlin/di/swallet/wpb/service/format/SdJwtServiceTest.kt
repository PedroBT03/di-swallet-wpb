package di.swallet.wpb.service.format

import di.swallet.wpb.format.sdjwt.SdJwtService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.nimbusds.jose.util.Base64URL

class SdJwtServiceTest {

    private val sdJwtService = SdJwtService()

    /**
     * Verifies that a disclosure is a valid Base64URL encoded JSON array.
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
     * Verifies that two disclosures for the same claim have different salts.
     */
    @Test
    fun `should produce unique disclosures for same claim due to random salts`() {
        val disclosure1 = sdJwtService.createDisclosure("age", 25)
        val disclosure2 = sdJwtService.createDisclosure("age", 25)
        
        assertNotEquals(disclosure1, disclosure2)
    }
}