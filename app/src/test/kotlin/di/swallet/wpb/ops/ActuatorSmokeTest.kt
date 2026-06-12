package di.swallet.wpb.ops

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.ConformanceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

@ConformanceTest
class ActuatorSmokeTest : BaseIntegrationTest() {

    @Test
    @ConformanceScenario("actuator_health_smoke")
    fun `actuator health reports UP with HSM and trust components`() {
        val response = restTemplate.getForEntity("/actuator/health", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val status = response.body?.get("status") as? String
        assertThat(status).isIn("UP", "DEGRADED")
        @Suppress("UNCHECKED_CAST")
        val components = response.body?.get("components") as? Map<String, Map<String, Any>>
        assertThat(components).containsKeys("hsm", "trustSnapshot")
    }

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
