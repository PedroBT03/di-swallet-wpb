/**
 * Smoke tests for actuator.
 */

package di.swallet.wpb.ops

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

@ConformanceTest
class ActuatorSmokeTest : BaseIntegrationTest() {

    /**
     * Hits /actuator/health for HSM and trustSnapshot plus the aggregate health endpoint
     * and expects known status values with hsm and trustSnapshot listed as components.
     */
    @Test
    @ConformanceScenario("actuator_health_smoke")
    fun `actuator health reports UP with HSM and trust components`() {
        val hsm = restTemplate.getForEntity("/actuator/health/hsm", Map::class.java)
        assertThat(hsm.body?.get("status")).isIn("UP", "DOWN")

        val trust = restTemplate.getForEntity("/actuator/health/trustSnapshot", Map::class.java)
        assertThat(trust.body?.get("status")).isIn("UP", "DEGRADED", "DOWN")

        val aggregate = restTemplate.getForEntity("/actuator/health", Map::class.java)
        assertThat(aggregate.statusCode).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE)
        @Suppress("UNCHECKED_CAST")
        val components = aggregate.body?.get("components") as? Map<String, Map<String, Any>>
        assertThat(components).containsKeys("hsm", "trustSnapshot")
    }

    /**
     * GETs /actuator/info and expects an operational section containing profile, demoMode,
     * and swaggerEnabled keys for deployment profile visibility.
     */
    @Test
    @ConformanceScenario("actuator_info_operational")
    fun `actuator info exposes operational profile metadata`() {
        val response = restTemplate.getForEntity("/actuator/info", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val operational = response.body?.get("operational") as? Map<String, Any>
        assertThat(operational).isNotNull
        assertThat(operational).containsKeys("profile", "demoMode", "swaggerEnabled")
    }
}
