package di.swallet.wpb.controller

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.domain.WalletKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.util.*

class StatusListPublicationTest : BaseIntegrationTest() {

    @Test
    fun `should publish revocation status list without authentication`() {
        val response = restTemplate.getForEntity(
            "/api/v1/wallet/status-lists/PRIMARY_LIST",
            Map::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("type")).isEqualTo("BitstringStatusList")
        assertThat(response.body?.get("statusPurpose")).isEqualTo("revocation")
        assertThat(response.body?.get("encodedList")).isNotNull
    }

    @Test
    fun `should expose revoked entry status for external verifier consumption`() {
        val userId = "status-list-user-${UUID.randomUUID()}"

        // Create key and then revoke it to toggle one bit in the status list.
        restTemplate.postForEntity(
            "/api/v1/wallet/keys/$userId",
            HttpEntity<String>(getDynamicHeaders(userId)),
            WalletKey::class.java
        )

        val revokeResponse = restTemplate.postForEntity(
            "/api/v1/wallet/keys/$userId/revoke",
            HttpEntity<String>(getDynamicHeaders(userId)),
            Map::class.java
        )
        assertThat(revokeResponse.statusCode).isEqualTo(HttpStatus.OK)

        val revokedIndex = (revokeResponse.body?.get("index") as String).toInt()

        val checkResponse = restTemplate.getForEntity(
            "/api/v1/wallet/status-lists/PRIMARY_LIST/entries/$revokedIndex",
            Map::class.java
        )

        assertThat(checkResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(checkResponse.body?.get("status")).isEqualTo("REVOKED")
        assertThat(checkResponse.body?.get("revoked")).isEqualTo(true)
    }
}
