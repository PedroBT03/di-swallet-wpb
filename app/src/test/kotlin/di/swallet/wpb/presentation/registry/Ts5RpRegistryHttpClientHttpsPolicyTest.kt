package di.swallet.wpb.presentation.registry

import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.ops.metrics.WpbMetricsTestSupport
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ts5RpRegistryHttpClientHttpsPolicyTest {

    @Test
    fun `production mode rejects http registry base url`() {
        val client = client(
            demoMode = false,
            baseUrl = "http://registry.europa.eu",
            allowedHosts = "registry.europa.eu",
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            client.getByIdentifier("rp-123")
        }
        assertTrue(ex.message?.contains("HTTPS", ignoreCase = true) == true)
    }

    @Test
    fun `production mode rejects https host outside allow-list`() {
        val client = client(
            demoMode = false,
            baseUrl = "https://registry.europa.eu",
            allowedHosts = "national-registry.example",
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            client.getByIdentifier("rp-123")
        }
        assertTrue(ex.message?.contains("allow-listed", ignoreCase = true) == true)
    }

    @Test
    fun `production mode requires non-empty host allow-list`() {
        val client = client(
            demoMode = false,
            baseUrl = "https://registry.europa.eu",
            allowedHosts = "",
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            client.getByIdentifier("rp-123")
        }
        assertTrue(ex.message?.contains("allow-list", ignoreCase = true) == true)
    }

    @Test
    fun `demo mode allows http localhost registry url`() {
        val client = client(
            demoMode = true,
            baseUrl = "http://127.0.0.1:8080/registry",
            allowedHosts = "",
        )
        assertDoesNotThrow {
            runCatching { client.getByIdentifier("rp-123") }
        }
    }

    @Test
    fun `production mode accepts allow-listed https host before transport`() {
        val client = client(
            demoMode = false,
            baseUrl = "https://registry.europa.eu",
            allowedHosts = "registry.europa.eu",
        )
        val ex = assertThrows(Exception::class.java) {
            client.getByIdentifier("rp-123")
        }
        assertTrue(
            ex.message?.contains("HTTPS", ignoreCase = true) != true &&
                ex.message?.contains("allow-listed", ignoreCase = true) != true,
        )
    }

    private fun client(
        demoMode: Boolean,
        baseUrl: String,
        allowedHosts: String,
    ): Ts5RpRegistryHttpClient {
        val props = OpenId4VpProperties().apply {
            this.demoMode = demoMode
            registry.baseUrl = baseUrl
            registry.remoteAllowedHosts = allowedHosts
            registry.connectTimeoutMs = 50
            registry.readTimeoutMs = 50
        }
        return Ts5RpRegistryHttpClient(props, WpbMetricsTestSupport.noop())
    }
}
