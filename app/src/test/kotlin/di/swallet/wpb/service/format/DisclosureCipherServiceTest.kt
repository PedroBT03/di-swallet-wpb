package di.swallet.wpb.service.format

import di.swallet.wpb.config.WalletProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DisclosureCipherServiceTest {

    private val props = WalletProperties(
        disclosures = WalletProperties.DisclosuresProperties(
            encryptionKey = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY="
        )
    )
    private val service = DisclosureCipherService(props)

    @Test
    fun `should encrypt and decrypt disclosures losslessly`() {
        val disclosures = listOf("disc-a", "disc-b", "disc-c")

        val encrypted = service.encrypt(disclosures)
        val decrypted = service.decrypt(encrypted)

        assertEquals(disclosures, decrypted)
    }

    @Test
    fun `should produce different ciphertext for same input`() {
        val disclosures = listOf("disc-a", "disc-b")

        val encrypted1 = service.encrypt(disclosures)
        val encrypted2 = service.encrypt(disclosures)

        assertNotEquals(encrypted1, encrypted2)
    }
}
